// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.VALID_TOC_BASE64
import app.accrescent.server.adapters.driven.file.LocalTempFile
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrapErr
import arrow.core.None
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import com.android.bundle.Commands
import com.android.bundle.Targeting
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.path.createTempFile
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes

// The current time passed to every parse() call in this file; the minimum target SDK it implies
// (35) is what the LowTargetSdk test relies on
private val CURRENT_TIME: OffsetDateTime = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

class ApkSetTest {
    @Test
    fun `parse returns InvalidFormat for invalid ZIP`(@TempDir tempDir: Path) {
        val path = createTempFile(tempDir)
        // The correct local file header is 504b0304
        // (https://en.wikipedia.org/wiki/ZIP_(file_format)#Local_file_header), so a single zero
        // does not start a valid ZIP file.
        path.writeBytes(byteArrayOf(0))

        val error = ApkSet.parse(path, tempDir, LocalTempFile, CURRENT_TIME).unwrapErr()

        assertEquals(ApkSetParseError.InvalidFormat, error)
    }

    @Test
    fun `parse returns InvalidFormat for ZIP with multiple entries with the same name`(@TempDir tempDir: Path) {
        val apkSetPath = duplicateEntryNameApkSetPath(tempDir)

        val error = ApkSet.parse(apkSetPath, tempDir, LocalTempFile, CURRENT_TIME).unwrapErr()

        assertEquals(ApkSetParseError.InvalidFormat, error)
    }

    @Test
    fun `parse returns InvalidFormat if table of contents has non-existent APK path`(@TempDir tempDir: Path) {
        val apkSetPath = missingApkApkSetPath(tempDir)

        val error = ApkSet.parse(apkSetPath, tempDir, LocalTempFile, CURRENT_TIME).unwrapErr()

        assertEquals(ApkSetParseError.InvalidFormat, error)
    }

    @Test
    fun `parse returns InvalidFormat if not all APKs are signed with the same certificate`(@TempDir tempDir: Path) {
        val apkSetPath = mismatchedSigningCertApkSetPath(tempDir)

        val error = ApkSet.parse(apkSetPath, tempDir, LocalTempFile, CURRENT_TIME).unwrapErr()

        assertEquals(ApkSetParseError.InvalidFormat, error)
    }

    @Test
    fun `parse returns InvalidFormat if not all APK app IDs match the table of contents`(@TempDir tempDir: Path) {
        val apkSetPath = mismatchedAppIdApkSetPath(tempDir)

        val error = ApkSet.parse(apkSetPath, tempDir, LocalTempFile, CURRENT_TIME).unwrapErr()

        assertEquals(ApkSetParseError.InvalidFormat, error)
    }

    @Test
    fun `parse returns Io if reading from path fails`(@TempDir tempDir: Path) {
        // Create a file we can't read so that we know the path we pass to ApkSet.parse() doesn't
        // contain a readable file
        val path = createTempFile(
            directory = tempDir,
            attributes = arrayOf(PosixFilePermissions.asFileAttribute(emptySet())),
        )

        val error = ApkSet.parse(path, tempDir, LocalTempFile, CURRENT_TIME).unwrapErr()

        assertEquals(ApkSetParseError.Io, error)
    }

    @Test
    fun `parse returns Missing64BitCode if app has 32-bit native code without corresponding 64-bit code`(
        @TempDir tempDir: Path,
    ) {
        val apkSetPath = missing64BitApkSetPath(tempDir)

        val error = ApkSet.parse(apkSetPath, tempDir, LocalTempFile, CURRENT_TIME).unwrapErr()

        assertEquals(ApkSetParseError.Policy.Missing64BitCode, error)
    }

    @Test
    fun `parse returns LowTargetSdk if app's target SDK is lower than the minimum`(@TempDir tempDir: Path) {
        // The low-target-sdk test data's target SDK (34) is below the minimum target SDK implied
        // by CURRENT_TIME (35)
        val error =
            ApkSet.parse(lowTargetSdkApkSetPath(), tempDir, LocalTempFile, CURRENT_TIME).unwrapErr()

        assertEquals(ApkSetParseError.Policy.LowTargetSdk, error)
    }

    @Test
    fun `parse returns the expected ApkSet for the valid APK set`(@TempDir tempDir: Path) {
        val expectedBuildApksResult =
            Commands.BuildApksResult.parseFrom(Base64.decode(VALID_TOC_BASE64))

        val apkSet = ApkSet.parse(validApkSetPath(), tempDir, LocalTempFile, CURRENT_TIME).unwrap()

        assertEquals(
            ApkSet(
                applicationId = ApplicationId.fromString("com.example.app").unwrap(),
                versionCode = VersionCode.fromInt(1).unwrap(),
                versionName = VersionName.fromString("1.0").unwrap(),
                targetSdk = SdkVersion.fromInt(37).unwrap(),
                permissions = mapOf(
                    NameAttribute.fromString("android.permission.INTERNET").unwrap() to None,
                ),
                signerCertificate = validSigningCert,
                buildApksResult = expectedBuildApksResult,
            ),
            apkSet,
        )
    }
}

private fun validApkSetPath(): Path {
    return Path.of(requireNotNull(System.getProperty("testdata.apkset.valid.path")) {
        "APK set test data was not built; run this test via the Gradle 'test' task"
    })
}

private fun lowTargetSdkApkSetPath(): Path {
    return Path.of(requireNotNull(System.getProperty("testdata.apkset.low-target-sdk.path")) {
        "APK set test data was not built; run this test via the Gradle 'test' task"
    })
}

private const val TOC_PATH = "toc.pb"

// The certificate used to sign the valid APK set test data, from
// testdata/android-app-valid/build.gradle.kts
private val validSigningCert = CertificateFactory
    .getInstance("X.509")
    .generateCertificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIBmjCCAUGgAwIBAgIIL/ZV8tyksMQwCgYIKoZIzj0EAwMwQTELMAkGA1UEBhMC
        VVMxEzARBgNVBAoTCkFjY3Jlc2NlbnQxHTAbBgNVBAMTFEFjY3Jlc2NlbnQgVGVz
        dCBEYXRhMCAXDTI1MTIzMDAwMzgwNVoYDzIwNTAxMjI0MDAzODA1WjBBMQswCQYD
        VQQGEwJVUzETMBEGA1UEChMKQWNjcmVzY2VudDEdMBsGA1UEAxMUQWNjcmVzY2Vu
        dCBUZXN0IERhdGEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATyiqc/LercXEgv
        d38Abqbw4BlIH46nV3tYoQnZzqTm+1RrhlqzgVViuw7S8/c/Bm8rCtpvOlyQpJMy
        hUp5MNNMoyEwHzAdBgNVHQ4EFgQUsIrVCtW/FY9akOgHQ5rNCTYdP/cwCgYIKoZI
        zj0EAwMDRwAwRAIgf+pJNAN8t0P78r28E0Dwnd5m9euS0lQpwiKmykf4dT4CIBUR
        U/eST77QsXax9VvGr/aTTXV3uf+ZWxt4joaW73Pu
        -----END CERTIFICATE-----
        """
            .trimIndent()
            .byteInputStream()
    ) as X509Certificate

// A self-signed EC test key and certificate distinct from the one used to sign the valid APK set
// test data, used only to produce an APK signed with a mismatched certificate. It only ever signs
// throwaway test data, so there's no harm in committing it.
private val secondSigningKey = KeyFactory
    .getInstance("EC")
    .generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.decode(
                "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgPGwwG730iKOL83yY4ZOqm2rBtb+YS4XuVHqlC" +
                        "/uO9nChRANCAARBE47aQ/JcWCCIHlYZLORtc8IlZW0PICPt1VMlyVg3kBELbOm5nuzwmID6Kz9l/X4uIh5D" +
                        "I7hQ270uK3sIqtV8"
            )
        )
    )
private val secondSigningCert = CertificateFactory
    .getInstance("X.509")
    .generateCertificate(
        Base64.decode(
            "MIIB3zCCAYWgAwIBAgIUe2qi0rjoXy3N5o5UsxlHFrywchIwCgYIKoZIzj0EAwIwRDEWMBQGA1UEAwwNU2Vjb25" +
                    "kIFNpZ25lcjEdMBsGA1UECgwUQWNjcmVzY2VudCBUZXN0IERhdGExCzAJBgNVBAYTAlVTMCAXDTI2MDcyNzIx" +
                    "MzI1NVoYDzIwNTMxMjEyMjEzMjU1WjBEMRYwFAYDVQQDDA1TZWNvbmQgU2lnbmVyMR0wGwYDVQQKDBRBY2Ny" +
                    "ZXNjZW50IFRlc3QgRGF0YTELMAkGA1UEBhMCVVMwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARBE47aQ/Jc" +
                    "WCCIHlYZLORtc8IlZW0PICPt1VMlyVg3kBELbOm5nuzwmID6Kz9l/X4uIh5DI7hQ270uK3sIqtV8o1MwUTAd" +
                    "BgNVHQ4EFgQUW+JcLlS/66mb1nk5b7GbH097hKMwHwYDVR0jBBgwFoAUW+JcLlS/66mb1nk5b7GbH097hKMw" +
                    "DwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiA5Mij51QW06oMrv0A5y4x9G8Uo+SDZg1lsfgne" +
                    "PZhYyQIhAPpdj2Xn85twY+UPxs3maRsoFYrW95WU2uovGeLm8BsH"
        ).inputStream()
    ) as X509Certificate

private fun mismatchedSigningCertApkSetPath(tempDir: Path): Path {
    val path = createTempFile(directory = tempDir, suffix = ".apks")

    ZipFile(validApkSetPath().toFile()).use { zipFile ->
        val targetEntry = zipFile.entries().asSequence().first { it.name != TOC_PATH }

        // Extract the target entry alone so it can be re-signed without holding the whole APK set,
        // or even the whole APK, in memory
        val unsignedApk = createTempFile(directory = tempDir, suffix = ".apk")
        zipFile.getInputStream(targetEntry).use { input ->
            unsignedApk.outputStream().use { input.copyTo(it) }
        }

        // Re-sign it with a different certificate so that not all APKs in the set are signed with
        // the same certificate
        val resignedApk = createTempFile(directory = tempDir, suffix = ".apk")
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "CERT",
            KeyConfig.Jca(secondSigningKey),
            listOf(secondSigningCert),
        ).build()
        ApkSigner
            .Builder(listOf(signerConfig))
            .setInputApk(unsignedApk.toFile())
            .setOutputApk(resignedApk.toFile())
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()

        ZipOutputStream(path.outputStream()).use { zipOut ->
            for (entry in zipFile.entries()) {
                zipOut.putNextEntry(ZipEntry(entry.name))
                if (entry.name == targetEntry.name) {
                    resignedApk.inputStream().use { it.copyTo(zipOut) }
                } else {
                    zipFile.getInputStream(entry).use { it.copyTo(zipOut) }
                }
                zipOut.closeEntry()
            }
        }
    }

    return path
}

private fun duplicateEntryNameApkSetPath(tempDir: Path): Path {
    val path = createTempFile(directory = tempDir, suffix = ".apks")

    ZipFile(validApkSetPath().toFile()).use { zipFile ->
        // Unlike java.util.zip.ZipOutputStream, this implementation permits duplicate entry names
        ZipArchiveOutputStream(path.toFile()).use { zipOut ->
            for (entry in zipFile.entries()) {
                zipOut.putArchiveEntry(ZipArchiveEntry(entry.name))
                zipFile.getInputStream(entry).use { it.copyTo(zipOut) }
                zipOut.closeArchiveEntry()
            }

            zipOut.putArchiveEntry(ZipArchiveEntry(TOC_PATH))
            zipOut.closeArchiveEntry()
        }
    }

    return path
}

private fun missingApkApkSetPath(tempDir: Path): Path {
    val path = createTempFile(directory = tempDir, suffix = ".apks")

    ZipFile(validApkSetPath().toFile()).use { zipFile ->
        // Drop a single APK entry, leaving a dangling reference to it in the table of contents
        val entryToDrop = zipFile.entries().asSequence().first { it.name != TOC_PATH }

        ZipOutputStream(path.outputStream()).use { zipOut ->
            for (entry in zipFile.entries()) {
                if (entry.name == entryToDrop.name) {
                    continue
                }

                zipOut.putNextEntry(ZipEntry(entry.name))
                zipFile.getInputStream(entry).use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }
        }
    }

    return path
}

private fun mismatchedAppIdApkSetPath(tempDir: Path): Path {
    val entries = ZipFile(validApkSetPath().toFile()).use { zipFile ->
        val buildApksResult = zipFile.getInputStream(zipFile.getEntry(TOC_PATH)).use {
            Commands.BuildApksResult.parseFrom(it)
        }
        val modifiedTocBytes = buildApksResult
            .toBuilder()
            .setPackageName("${buildApksResult.packageName}.mismatched")
            .build()
            .toByteArray()

        zipFile.entries().asSequence().map { entry ->
            val bytes = if (entry.name == TOC_PATH) {
                modifiedTocBytes
            } else {
                zipFile.getInputStream(entry).use { it.readBytes() }
            }
            entry.name to bytes
        }.toList()
    }

    val path = createTempFile(directory = tempDir, suffix = ".apks")
    ZipOutputStream(path.outputStream()).use { zipOut ->
        for ((name, bytes) in entries) {
            zipOut.putNextEntry(ZipEntry(name))
            zipOut.write(bytes)
            zipOut.closeEntry()
        }
    }

    return path
}

private fun missing64BitApkSetPath(tempDir: Path): Path {
    val entries = ZipFile(validApkSetPath().toFile()).use { zipFile ->
        val buildApksResult = zipFile.getInputStream(zipFile.getEntry(TOC_PATH)).use {
            Commands.BuildApksResult.parseFrom(it)
        }
        val modifiedVariants = buildApksResult.variantList.map { variant ->
            variant
                .toBuilder()
                .setTargeting(
                    variant.targeting
                        .toBuilder()
                        .setAbiTargeting(
                            Targeting.AbiTargeting
                                .newBuilder()
                                .addValue(
                                    Targeting.Abi
                                        .newBuilder()
                                        .setAlias(Targeting.Abi.AbiAlias.ARMEABI_V7A)
                                )
                        )
                )
                .build()
        }
        val modifiedTocBytes = buildApksResult
            .toBuilder()
            .clearVariant()
            .addAllVariant(modifiedVariants)
            .build()
            .toByteArray()

        zipFile.entries().asSequence().map { entry ->
            val bytes = if (entry.name == TOC_PATH) {
                modifiedTocBytes
            } else {
                zipFile.getInputStream(entry).use { it.readBytes() }
            }
            entry.name to bytes
        }.toList()
    }

    val path = createTempFile(directory = tempDir, suffix = ".apks")
    ZipOutputStream(path.outputStream()).use { zipOut ->
        for ((name, bytes) in entries) {
            zipOut.putNextEntry(ZipEntry(name))
            zipOut.write(bytes)
            zipOut.closeEntry()
        }
    }

    return path
}
