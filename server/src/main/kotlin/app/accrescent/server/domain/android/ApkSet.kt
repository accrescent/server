// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.UseError
import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.core.use
import app.accrescent.server.domain.ports.driven.file.TempFile
import app.accrescent.server.domain.ports.driven.file.TempFileCloseError
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.android.bundle.Commands
import com.android.bundle.Targeting
import com.google.protobuf.InvalidProtocolBufferException
import java.io.IOException
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.time.OffsetDateTime
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.io.path.outputStream

private const val TOC_PATH = "toc.pb"

/**
 * The metadata of a parsed Android
 * [APK set](https://developer.android.com/tools/bundletool#generate_apks).
 *
 * This class is designed for internal use when parsing and is not a general-purpose APK set parser.
 */
data class ApkSet(
    val applicationId: ApplicationId,
    val versionCode: VersionCode,
    val versionName: VersionName,
    val targetSdk: SdkVersion,
    val permissions: Map<NameAttribute, Option<SdkVersion>>,
    val signerCertificate: X509Certificate,
    val buildApksResult: Commands.BuildApksResult,
) {
    companion object {
        /**
         * Parses an APK set from a file on the local filesystem.
         *
         * @param path the path to read the APK set from.
         * @param workspaceDir the directory to use as a workspace for temporary files, e.g.,
         * extracted APKs.
         * @param tempFileFactory the factory to use to create temporary files in [workspaceDir].
         * @param currentTime the current time, used to determine the current minimum target SDK.
         */
        fun parse(
            path: Path,
            workspaceDir: Path,
            tempFileFactory: TempFile.Factory<*>,
            currentTime: OffsetDateTime,
        ): Either<ApkSetParseError, ApkSet> = either {
            try {
                ZipFile(path.toFile())
            } catch (_: ZipException) {
                raise(ApkSetParseError.InvalidFormat)
            } catch (_: IOException) {
                raise(ApkSetParseError.Io)
            }.use { zipFile ->
                ensure(zipFile.entriesAreUnique()) { ApkSetParseError.InvalidFormat }

                val tocEntry = zipFile.getEntry(TOC_PATH) ?: raise(ApkSetParseError.InvalidFormat)
                val buildApksResult = try {
                    zipFile.getInputStream(tocEntry).use { Commands.BuildApksResult.parseFrom(it) }
                } catch (_: InvalidProtocolBufferException) {
                    raise(ApkSetParseError.InvalidFormat)
                } catch (_: IOException) {
                    raise(ApkSetParseError.Io)
                }

                val applicationId = ApplicationId
                    .fromString(buildApksResult.packageName)
                    .toEitherBind { ApkSetParseError.InvalidFormat }

                // Check compliance with 64-bit requirement
                //
                // https://accrescent.app/docs/guide/appendix/requirements.html#native-code
                for (variant in buildApksResult.variantList) {
                    val supportedAbis = variant.targeting.abiTargeting.valueList.map { it.alias }
                    val arm32Supported = supportedAbis.contains(Targeting.Abi.AbiAlias.ARMEABI_V7A)
                    val arm64Supported = supportedAbis.contains(Targeting.Abi.AbiAlias.ARM64_V8A)
                    val x86Supported = supportedAbis.contains(Targeting.Abi.AbiAlias.X86)
                    val x8664Supported = supportedAbis.contains(Targeting.Abi.AbiAlias.X86_64)

                    ensure((!arm32Supported || arm64Supported) && (!x86Supported || x8664Supported)) {
                        ApkSetParseError.Policy.Missing64BitCode
                    }
                }

                var pinnedSignerCertificate: Option<X509Certificate> = None
                var pinnedVersionCode: Option<VersionCode> = None
                var firstEncounteredVersionName: Option<VersionName> = None
                var lowestTargetSdk: Option<SdkVersion> = None
                val permissions = mutableMapOf<NameAttribute, Option<SdkVersion>>()
                for (apkPath in buildApksResult.apkPaths()) {
                    val apkEntry = zipFile.getEntry(apkPath) ?: raise(ApkSetParseError.InvalidFormat)
                    val apk = try {
                        zipFile.getInputStream(apkEntry).use { apkInputStream ->
                            tempFileFactory
                                .createInDirectory(workspaceDir)
                                .bindMapLeft { ApkSetParseError.Io }
                                .use { tempApk ->
                                    tempApk.path.outputStream().use { apkInputStream.copyTo(it) }

                                    Apk.parse(tempApk.path).bind()
                                }
                                .bindMapLeft(::toApkSetParseError)
                        }
                    } catch (_: ZipException) {
                        raise(ApkSetParseError.InvalidFormat)
                    } catch (_: IOException) {
                        raise(ApkSetParseError.Io)
                    }

                    // Check that the application ID of every APK matches the table of
                    // contents to ensure the app is installable
                    //
                    // https://developer.android.com/reference/android/content/pm/PackageInstaller
                    ensure(apk.manifest.applicationId == applicationId) {
                        ApkSetParseError.InvalidFormat
                    }

                    // Check that every APK is signed with the same certificate to ensure that 1) we
                    // have an accurate view of how the app is signed and 2) the app is installable
                    //
                    // https://developer.android.com/reference/android/content/pm/PackageInstaller
                    when (pinnedSignerCertificate) {
                        None -> pinnedSignerCertificate = Some(apk.signerCertificate)
                        is Some -> ensure(apk.signerCertificate == pinnedSignerCertificate.value) {
                            ApkSetParseError.InvalidFormat
                        }
                    }

                    // Check that the version code of every APK is the same to ensure the app is
                    // installable
                    //
                    // https://developer.android.com/reference/android/content/pm/PackageInstaller
                    when (pinnedVersionCode) {
                        None -> pinnedVersionCode = Some(apk.manifest.versionCode)
                        is Some -> ensure(apk.manifest.versionCode == pinnedVersionCode.value) {
                            ApkSetParseError.InvalidFormat
                        }
                    }

                    // Master-split-specific checks
                    if (apk.manifest.splitId.isNone()) {
                        // Save the version name of the first encountered master split for the
                        // app's overall version name
                        if (firstEncounteredVersionName is None) {
                            firstEncounteredVersionName = Some(
                                apk.manifest.versionName.toEitherBind { ApkSetParseError.InvalidFormat }
                            )
                        }

                        // Save the lowest encountered target SDK for target SDK compliance checking
                        lowestTargetSdk = Some(
                            lowestTargetSdk
                                .getOrElse { apk.manifest.targetSdkVersion }
                                .coerceAtMost(apk.manifest.targetSdkVersion)
                        )

                        // Accumulate the app's requested permissions across all master splits,
                        // deduplicating by name. Permissions with different max SDK versions across
                        // splits are ambiguous, so we reject APK sets with them (though we can
                        // probably use the most restrictive one instead if we need to be more
                        // lenient)
                        for ((name, maxSdkVersion) in apk.manifest.permissions) {
                            val pinnedMaxSdkVersion = permissions.putIfAbsent(name, maxSdkVersion)
                            ensure(
                                pinnedMaxSdkVersion == null ||
                                        maxSdkVersion == pinnedMaxSdkVersion
                            ) {
                                ApkSetParseError.InvalidFormat
                            }
                        }
                    }
                }

                // Check compliance with the target SDK requirement
                //
                // https://accrescent.app/docs/guide/appendix/requirements.html#target-sdk
                ensure(lowestTargetSdk is Some) { ApkSetParseError.InvalidFormat }
                ensure(lowestTargetSdk.value >= MinTargetSdkEvaluator.getMinTargetSdk(currentTime)) {
                    ApkSetParseError.Policy.LowTargetSdk
                }

                ensure(
                    pinnedSignerCertificate is Some
                            && pinnedVersionCode is Some
                            && firstEncounteredVersionName is Some
                ) {
                    ApkSetParseError.InvalidFormat
                }

                ApkSet(
                    applicationId = applicationId,
                    versionCode = pinnedVersionCode.value,
                    versionName = firstEncounteredVersionName.value,
                    targetSdk = lowestTargetSdk.value,
                    permissions = permissions,
                    signerCertificate = pinnedSignerCertificate.value,
                    buildApksResult = buildApksResult,
                )
            }
        }

        /**
         * Returns whether all of this ZIP file's entries have unique names.
         */
        private fun ZipFile.entriesAreUnique(): Boolean {
            val names = entries().asSequence().map { it.name }
            val seen = mutableSetOf<String>()

            return names.all(seen::add)
        }
    }
}

/**
 * An error that occurred while attempting to parse an APK set.
 */
sealed class ApkSetParseError {
    /**
     * The APK set's file data was invalid.
     */
    data object InvalidFormat : ApkSetParseError()

    /**
     * An I/O error occurred while parsing the APK set.
     */
    data object Io : ApkSetParseError()

    /**
     * The APK set is valid, but it violates an Accrescent policy.
     */
    sealed class Policy : ApkSetParseError() {
        /**
         * At least one of the APK set's APKs violates an Accrescent policy.
         *
         * @property error the original APK policy error.
         */
        data class Apk(val error: ApkParseError.Policy) : Policy()

        /**
         * The app has 32-bit native code without corresponding 64-bit native code.
         */
        data object Missing64BitCode : Policy()

        /**
         * The app's effective target SDK is lower than the current minimum.
         */
        data object LowTargetSdk : Policy()
    }
}

private fun toApkSetParseError(error: ApkParseError): ApkSetParseError {
    return when (error) {
        ApkParseError.InvalidFormat -> ApkSetParseError.InvalidFormat
        ApkParseError.Io -> ApkSetParseError.Io
        ApkParseError.UnsupportedAlgorithm -> ApkSetParseError.InvalidFormat
        is ApkParseError.Policy -> ApkSetParseError.Policy.Apk(error)
    }
}

private fun toApkSetParseError(error: UseError<ApkParseError, TempFileCloseError>): ApkSetParseError {
    return when (error) {
        is UseError.Block -> toApkSetParseError(error.error)
        // Prioritize returning the block error over the close error if both occurred
        is UseError.Both -> toApkSetParseError(error.blockError)
        is UseError.Close -> ApkSetParseError.Io
    }
}
