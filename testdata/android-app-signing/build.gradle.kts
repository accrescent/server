// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

import app.accrescent.server.build.ApkAttr
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.attributes.BuildTypeAttr
import org.apache.commons.compress.archivers.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.io.encoding.Base64

// Fixed signing material (base64-encoded DER) so the generated APKs are reproducible. These keys
// only ever sign throwaway test data, so there's no harm in committing them. The primary and second
// signers are self-signed EC certificates; the debug signer is an RSA certificate whose subject
// distinguished name matches the one in the Android debug keystore.
private val primaryKey = privateKey(
    "EC",
    "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDp6XDVbuxKqH9midzmAsgJRW3Y4U3cYHIAdFqCDXSg9A==",
)
private val primaryCert = certificate(
    "MIIBmjCCAUGgAwIBAgIIA/uKRVelGWUwCgYIKoZIzj0EAwMwQTEdMBsGA1UEAxMUQWNjcmVzY2VudCBUZXN0IERhdGE" +
            "xEzARBgNVBAoTCkFjY3Jlc2NlbnQxCzAJBgNVBAYTAlVTMCAXDTI2MDcwNjIzNDUzM1oYDzIwNTMxMTIxMj" +
            "M0NTMzWjBBMR0wGwYDVQQDExRBY2NyZXNjZW50IFRlc3QgRGF0YTETMBEGA1UEChMKQWNjcmVzY2VudDELM" +
            "AkGA1UEBhMCVVMwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARhyL9frcRz6fIQpqWpgru+XKkseCqMoNlx" +
            "YWYKhv42q0F+IwDFjesckb0G1V7D4Cq6eLVsLHRmwKaT/QaP3LS8oyEwHzAdBgNVHQ4EFgQULCiFj9ywawH" +
            "e+X2Rj46ZTQUjWREwCgYIKoZIzj0EAwMDRwAwRAIgTd4arS6No7OVEdSXt/FOMLwYPvpFCdDH4UgL5EkH2W" +
            "kCIGLNb3b//JCI8CJbIRuGWXIAt1xIOqJwnKtImmUubMDR",
)
private val secondKey = privateKey(
    "EC",
    "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBO1dWtNo0nKR0BwjjGLYLSbkrktIf4guLb9Fk5A8HXmw==",
)
private val secondCert = certificate(
    "MIIBjTCCATSgAwIBAgIJAPc2/wG5hqAtMAoGCCqGSM49BAMDMDoxFjAUBgNVBAMTDVNlY29uZCBTaWduZXIxEzARBgN" +
            "VBAoTCkFjY3Jlc2NlbnQxCzAJBgNVBAYTAlVTMCAXDTI2MDcwNjIzNDUzNFoYDzIwNTMxMTIxMjM0NTM0Wj" +
            "A6MRYwFAYDVQQDEw1TZWNvbmQgU2lnbmVyMRMwEQYDVQQKEwpBY2NyZXNjZW50MQswCQYDVQQGEwJVUzBZM" +
            "BMGByqGSM49AgEGCCqGSM49AwEHA0IABB0ndw2Sw+EKL0l6/9oIDdCLpMeNeo2CvQdpb8CcQQN/fp1N/FWk" +
            "x2jdUas+48AvjjEaDAHqpzwxVrP67MdCfFmjITAfMB0GA1UdDgQWBBQc5fsVhgaiH87d7AZ+F7zGg249HDA" +
            "KBggqhkjOPQQDAwNHADBEAiB9dfFxjq5Xe7Pid3nOF6wiygtaH8e2ujC5ecVVHkDjCQIgE1lGeTs9u7Qbjk" +
            "4dTD+ybzCmcIpcZ2MCBCdcLoVo52o=",
)
private val debugKey = privateKey(
    "RSA",
    "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC/i8viytISVOUWH+nF6QChSdx+KtHO9BhnEHnKKMl" +
            "LqglNm2++TgRuTFgfRJtJGnsDQrlfUVdacMMNrIs2XQ04yVX9Ny6eLOYWey0tFJ6U/APmhKH+A6u5CpZ5lm" +
            "yQEamECxFjsvnbj7maFmh5M1xYm7J3GSsfPSm+WL+sGGNaCxVBxgLPE/K85dMdLUI4cRI/ouba/UtFAytoK" +
            "oufhGknAwCGgqv3IDabNOdWpJOUbydbTJ4HDRkJi078Gtr5Xnr5D0ZbTev3nwbGr8Je5mFnC4F+I1QzPqYD" +
            "6Os6ZmvODpzeuULqqwFYP4QsPRT81x0eLBY9NF37wtWx8/Xcrpf1AgMBAAECggEAUPfQFKs1h6g5OlP9Jrq" +
            "qmIM3YGHLVJccJZzyToFVLdXPnu2gm6ow90rwSS9gENPPwf4Xi/I/Yaye1w3jvvQwnZuF7MbBvv0tub0RyK" +
            "eZrVKmd3ADZfO5SDvwha8Pbwr4RCfFjwZd1fD4POlR+kG6vH38P5n0/3yEQ8ESLLYWcT3Y45D5tIcGzy1c+" +
            "XL+CDJy2AiM3q0ta0bUvqOCIwFCWc78EBvkGS5oCvhTC+pFqPDErSfscrvDmjL8I1TeAcShq4wMXf2dBLcv" +
            "awZxOLFP/Q8k3QIPJPfoZGpsB5w7amzojPhBQCDGNwlqXxgvP02KFVBQ0PGEKDRnuP8bMI3S1QKBgQDfSUn" +
            "fEFd5vNekUOzNArYGUE7c+i9ZsaMQQ/AwlKR0ruQU7wW0PEPGPNxKx7gMHPbN51UR10KGRSnOVbybqMJ+kx" +
            "ve/ACn0rpP4928kkXVJkYRTIk+j1IEDiQxR6YuQTEeIRwy4crbMbVVNuNNh/W9O7pOUHdF92cwx0PhK3mbY" +
            "wKBgQDbnAparWvaJc2DhRtJXpIlaFMdimbeurYlyrpMMmdUnFUqWH5XuTYoEtz/BuB26g/wt7TQS4yG5vRt" +
            "6mr8w/UQXbqg7u7VZf5WjqMmkPQworIR8dTRcUGSSzO7ntwesrja8CKcGFg2ao5Y6EKu2lQkhcOW2BUMLDy" +
            "Fh/D/C0JaxwKBgF5f5600hwSZYLu+yJfOMYlxoCOeSy0p+7YAQSEHcosu8JA4hryrTCoZxzKEP7I++8IDqN" +
            "0lkqSVzxm6+0D/j1VYcEtLUCue0ci6kxoE6ScySiM9qjSa9xtnrrx1gDPEsjH95KFM9iS3WeFulZxLfv5Ap" +
            "Ho9YGxeKWtgjbw6V+fpAoGBAK6RAA4KcoQKrq2QHhuZSDTiE8eUn6cG5glud95f5pF0X6J0i6GxwSHjtLYo" +
            "Qj9kWV1wuhMlKsSFS+EfiQH5xpDG4LZSl5kcJSuq4Hekm+cZPNFU2WXPUF841hua8MCaMqUeY3SPSXegBh0" +
            "YKwGQ3XfWuJ3sj/aIJ2fBoskpqwo7AoGAZqor7fob/5LWGyXQM9kFHciRiQgqXdZVTQv+bZmMUAEfEd3zfb" +
            "D1GHRIRgf9z3/QQwhEwUWO+wVCt3jnHaweyQno2C8YYJytvWGhJmu/MGqhKOwt8A6symOozkP3a9JzjB/d0" +
            "3e/ibvXJ2YGbj+ziaml+FYUor8IaKj60f4YfYs=",
)
private val debugCert = certificate(
    "MIIDEzCCAfugAwIBAgIIWDUo4Ihyw+EwDQYJKoZIhvcNAQEMBQAwNzELMAkGA1UEBhMCVVMxEDAOBgNVBAoTB0FuZHJ" +
            "vaWQxFjAUBgNVBAMTDUFuZHJvaWQgRGVidWcwIBcNMjYwNzA2MjM0NTM0WhgPMjA1MzExMjEyMzQ1MzRaMD" +
            "cxCzAJBgNVBAYTAlVTMRAwDgYDVQQKEwdBbmRyb2lkMRYwFAYDVQQDEw1BbmRyb2lkIERlYnVnMIIBIjANB" +
            "gkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAv4vL4srSElTlFh/pxekAoUncfirRzvQYZxB5yijJS6oJTZtv" +
            "vk4EbkxYH0SbSRp7A0K5X1FXWnDDDayLNl0NOMlV/TcunizmFnstLRSelPwD5oSh/gOruQqWeZZskBGphAs" +
            "RY7L524+5mhZoeTNcWJuydxkrHz0pvli/rBhjWgsVQcYCzxPyvOXTHS1COHESP6Lm2v1LRQMraCqLn4RpJw" +
            "MAhoKr9yA2mzTnVqSTlG8nW0yeBw0ZCYtO/Bra+V56+Q9GW03r958Gxq/CXuZhZwuBfiNUMz6mA+jrOmZrz" +
            "g6c3rlC6qsBWD+ELD0U/NcdHiwWPTRd+8LVsfP13K6X9QIDAQABoyEwHzAdBgNVHQ4EFgQUnGtQwRsQg7X5" +
            "OPDuk7pyxHkVU6cwDQYJKoZIhvcNAQEMBQADggEBAEpxDzrROpJwPKTDW0iSf2HhAom3dpLmm+CrXj4Dg/g" +
            "eNq5gp7tFfyGKwfl69s+dJCHW5XNzvEqOzbqUVHbeBBEmyr7u7IBRRx3bMgp/IhQ4YVNoa+41MXr+d9piwD" +
            "vpXOg0CtfmNyxaZFSCL/fKasJZn2QaLLrAiKfe7m6lCiaO6dEtaA9dlcqKygfnAO5h+ZqHrLJk4SsoEbjet" +
            "kymbtkKUEOwS/0bZIbxjGQ9YfR/YgoVl3rT6lu3g6ASulLtog9vY9sm+E+2n5GYzhyIET3WUt87+PGSz+pH" +
            "dRaNzpAobbS5hVDLYv0u7HJ5H/Kf6wgCqH0K7TDO65MmtOFxtQs=",
)
private val primarySigner = signerConfig("CERT", primaryKey, primaryCert)
private val secondSigner = signerConfig("SIGNER2", secondKey, secondCert)
private val debugSigner = signerConfig("DEBUG", debugKey, debugCert)

plugins {
    alias(libs.plugins.android.application)
    id("app.accrescent.server.build.apk-attributes")
}

// The server module depends on this module's APK test data by these coordinates; the group lets the
// root build automatically substitute that dependency for this included build's project.
group = "app.accrescent.server.testdata"

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

android {
    namespace = "com.example.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 26
        // Below API 30 so that apksig accepts a v1-only signature, which the "no-modern-signature"
        // APK relies on. The v2/v3-signed APKs are unaffected by this.
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // targetSdk is intentionally below the latest to exercise the v1-only signature path.
        disable += "ExpiredTargetSdkVersion"
    }
}

// Each key is both the signing scenario and the output APK's file name (without extension); the
// server derives the "testdata.apk.<name>.path" system property from it. Each value signs the
// unsigned APK at the first path into the second.
private val signingScenarios = mapOf<String, (Path, Path) -> Unit>(
    // Signed with only a v1 (JAR) signature.
    "no-modern-signature" to { unsignedApk, output ->
        signApk(
            unsignedApk,
            output,
            listOf(primarySigner),
            v1Enabled = true,
            v2Enabled = false,
            v3Enabled = false,
        )
    },

    // Signed by two signers. v3 disallows multiple signers, so use v1 + v2.
    "multiple-certs" to { unsignedApk, output ->
        signApk(
            unsignedApk,
            output,
            listOf(primarySigner, secondSigner),
            v1Enabled = true,
            v2Enabled = true,
            v3Enabled = false,
        )
    },

    // Signed with a debug certificate.
    "debug-cert" to { unsignedApk, output ->
        signApk(
            unsignedApk,
            output,
            listOf(debugSigner),
            v1Enabled = false,
            v2Enabled = true,
            v3Enabled = true,
        )
    },

    // Validly signed, then a byte of classes.dex is flipped so the v2 digest no longer matches and
    // the signature fails to verify.
    "unverified" to { unsignedApk, output ->
        signApk(
            unsignedApk,
            output,
            listOf(primarySigner),
            v1Enabled = false,
            v2Enabled = true,
            v3Enabled = false,
        )
        corruptZipEntry(output, "classes.dex")
    },
)

// For each variant, register tasks which sign the unsigned APK in the various ways exercised by
// Apk.parse's signature checks, and expose the results through a consumable configuration.
androidComponents.onVariants { variant ->
    val apkElements = configurations.consumable("${variant.name}ApkElements") {
        attributes.attribute(
            ApkAttr.ATTRIBUTE,
            objects.named(ApkAttr::class.java, ApkAttr.PRESENT),
        )
        attributes.attribute(
            BuildTypeAttr.ATTRIBUTE,
            objects.named(BuildTypeAttr::class, variant.buildType!!),
        )
    }

    val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
    val builtArtifactsLoader = variant.artifacts.getBuiltArtifactsLoader()

    for ((apkName, sign) in signingScenarios) {
        val signApkTask = tasks.register(
            "sign${variant.name.toPascalCase()}${apkName.toPascalCase()}",
        ) {
            inputs.files(apkDirectory)

            val outputApk = layout.buildDirectory.file(
                Paths.get("outputs", "signed-apk", variant.name, "$apkName.apk").toString(),
            )
            outputs.file(outputApk)

            doLast {
                val builtArtifacts = builtArtifactsLoader.load(apkDirectory.get())!!
                val unsignedApk = Paths.get(builtArtifacts.elements.single().outputFile)

                sign(unsignedApk, outputApk.get().asFile.toPath())
            }
        }

        artifacts.add(apkElements.name, signApkTask.map { it.outputs.files.singleFile })
    }
}

private fun String.toPascalCase(): String =
    split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }

private fun privateKey(algorithm: String, base64: String): PrivateKey =
    KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(Base64.decode(base64)))

private fun certificate(base64: String): X509Certificate =
    Base64.decode(base64).inputStream().use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

private fun signerConfig(
    name: String,
    key: PrivateKey,
    cert: X509Certificate,
): ApkSigner.SignerConfig =
    ApkSigner.SignerConfig.Builder(name, KeyConfig.Jca(key), listOf(cert)).build()

private fun signApk(
    input: Path,
    output: Path,
    signerConfigs: List<ApkSigner.SignerConfig>,
    v1Enabled: Boolean,
    v2Enabled: Boolean,
    v3Enabled: Boolean,
) {
    Files.createDirectories(output.parent)
    Files.deleteIfExists(output)
    ApkSigner.Builder(signerConfigs)
        .setInputApk(input.toFile())
        .setOutputApk(output.toFile())
        .setV1SigningEnabled(v1Enabled)
        .setV2SigningEnabled(v2Enabled)
        .setV3SigningEnabled(v3Enabled)
        .build()
        .sign()
}

// Flips the first byte of the named ZIP entry's file data. This invalidates the v2 signature's
// digest over the ZIP entries without corrupting the ZIP structure or AndroidManifest.xml (which
// apksig reads while verifying), so the APK parses but fails to verify.
private fun corruptZipEntry(apk: Path, entryName: String) {
    val dataOffset = ZipFile.Builder().setPath(apk).get().use { zipFile ->
        requireNotNull(zipFile.getEntry(entryName)) { "entry not found: $entryName" }.dataOffset
    }

    RandomAccessFile(apk.toFile(), "rw").use { file ->
        file.seek(dataOffset)
        val corruptedByte = file.read() xor 0xff
        file.seek(dataOffset)
        file.write(corruptedByte)
    }
}
