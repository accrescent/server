// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.domain.android.xml.XmlDocument
import arrow.core.Either
import arrow.core.raise.either
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkFormatException
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.NoSuchAlgorithmException
import java.security.cert.X509Certificate

/**
 * The metadata of a parsed Android APK.
 *
 * This class is desgined for internal use when parsing an APK contained within an APK set and is
 * not a general-purpose APK parser.
 *
 * @property manifest the APK's Android manifest.
 * @property signerCertificate the APK's signer certificate.
 */
class Apk private constructor(
    val manifest: AndroidManifest,
    val signerCertificate: X509Certificate,
) {
    companion object {
        /**
         * Parses an APK from a file on the local filesystem.
         *
         * @param path the path to read the APK from.
         */
        fun parse(path: Path): Either<ApkParseError, Apk> = either {
            val verificationResult = try {
                ApkVerifier.Builder(path.toFile()).build().verify()
            } catch (_: IOException) {
                raise(ApkParseError.Io)
            } catch (_: ApkFormatException) {
                raise(ApkParseError.InvalidFormat)
            } catch (_: NoSuchAlgorithmException) {
                raise(ApkParseError.UnsupportedAlgorithm)
            }
            when {
                // Check that APK signature verifies to ensure the app is installable.
                //
                // https://source.android.com/docs/security/features/apksigning/v3#verification
                !verificationResult.isVerified -> raise(ApkParseError.Policy.Unverified)

                // Check compliance with signature scheme requirement.
                //
                // https://source.android.com/docs/security/features/apksigning/v3#verification
                !verificationResult.isVerifiedUsingModernScheme() ->
                    raise(ApkParseError.Policy.NoModernSignature)

                // Check compliance with debug certificate requirement.
                //
                // https://accrescent.app/docs/guide/appendix/requirements.html#debug-certificate
                verificationResult.signerCertificates.any { it.isDebug() } ->
                    raise(ApkParseError.Policy.SignedWithDebugCert)

                // Check compliance with multiple signers requirement.
                //
                // https://accrescent.app/docs/guide/appendix/requirements.html#multiple-signers
                verificationResult.signerCertificates.size > 1 ->
                    raise(ApkParseError.Policy.SignedWithMultipleCerts)
            }

            val manifest = try {
                RandomAccessFile(path.toFile(), "r").use {
                    DataSources
                        .asDataSource(it)
                        .let(ApkUtils::getAndroidManifest)
                }
            } catch (_: IOException) {
                raise(ApkParseError.Io)
            } catch (_: ApkFormatException) {
                raise(ApkParseError.InvalidFormat)
            }
                .let(XmlDocument::fromBinaryXml)
                .bindMapLeft { ApkParseError.InvalidFormat }
                .let(AndroidManifest::fromXmlDocument)
                .bindMapLeft(::toApkError)
            val signerCertificate = verificationResult.signerCertificates[0]

            Apk(manifest, signerCertificate)
        }
    }
}

/**
 * An error that occurred while attempting to parse an APK.
 */
sealed class ApkParseError {
    /**
     * The APK's file data was invalid.
     */
    data object InvalidFormat : ApkParseError()

    /**
     * An I/O error occurred while parsing the APK.
     */
    data object Io : ApkParseError()

    /**
     * The APK is valid, but it violates an Accrescent policy.
     */
    sealed class Policy : ApkParseError() {
        /**
         * The APK's manifest violates Accrescent policy.
         *
         * @property error the original manifest policy error.
         */
        data class Manifest(val error: AndroidManifest.FromXmlError.Policy) : Policy()

        /**
         * The APK was signed with only a legacy (v1) signature.
         *
         * v1 signatures are
         * [weaker and less efficient](https://source.android.com/docs/security/features/apksigning#v1)
         * than v2+ signatures and v2+ signatures are
         * [required](https://developer.android.com/about/versions/11/behavior-changes-11#minimum-signature-scheme)
         * when targeting API 30+ (Android 11+), so we shouldn't allow them.
         */
        data object NoModernSignature : Policy()

        /**
         * The APK was signed with a debug certificate.
         *
         * Debug certificates are
         * [insecure by design](https://developer.android.com/studio/publish/app-signing#debug-mode),
         * so they are unsuitable for use in a general purpose app store.
         */
        data object SignedWithDebugCert : Policy()

        /**
         * The APK was signed with multiple certificates.
         */
        data object SignedWithMultipleCerts : Policy()

        /**
         * The APK's signature did not verify.
         *
         * APKs must have a valid signature to be installable.
         */
        data object Unverified : Policy()
    }

    /**
     * An unsupported cryptographic algorithm was required to validate this APK's signature.
     */
    data object UnsupportedAlgorithm : ApkParseError()
}

// The distinguished name of the certificate in the Android debug keystore, as rendered by
// X500Principal.getName(). See com.android.ide.common.signing.KeystoreHelper.
private const val DEBUG_CERT_PRINCIPAL_NAME = "CN=Android Debug,O=Android,C=US"

private fun ApkVerifier.Result.isVerifiedUsingModernScheme(): Boolean {
    return isVerifiedUsingV2Scheme || isVerifiedUsingV3Scheme || isVerifiedUsingV31Scheme
}

private fun X509Certificate.isDebug(): Boolean {
    return subjectX500Principal.name == DEBUG_CERT_PRINCIPAL_NAME
}

private fun toApkError(error: AndroidManifest.FromXmlError): ApkParseError {
    return when (error) {
        AndroidManifest.FromXmlError.InvalidManifest -> ApkParseError.InvalidFormat
        is AndroidManifest.FromXmlError.Policy -> ApkParseError.Policy.Manifest(error)
    }
}
