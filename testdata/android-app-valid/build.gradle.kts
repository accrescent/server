// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

import app.accrescent.server.build.ApkSetAttr
import app.accrescent.server.build.ApkSetNameAttr
import com.android.SdkConstants
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.attributes.BuildTypeAttr
import com.android.repository.Revision
import com.android.sdklib.BuildToolInfo
import com.android.tools.build.bundletool.androidtools.Aapt2Command
import com.android.tools.build.bundletool.commands.BuildApksCommand
import com.android.tools.build.bundletool.model.SigningConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.io.encoding.Base64

private val apkSetName = "valid"

private val signingKey = run {
    val encodedKey = """
        MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAG87Eun2BB2uNw2kzYljbC9kuQVhCzQuf+5f3SDQV4Yg==
        """
        .trimIndent()
    val keyBytes = Base64.decode(encodedKey)

    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
}
private val signingCert = run {
    val pem = """
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

    pem
        .byteInputStream()
        .use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
}

plugins {
    alias(libs.plugins.android.application)
    id("app.accrescent.server.build.apk-set-attributes")
}

// The server module depends on this module's APK set test data by these coordinates; the group lets
// the root build automatically substitute that dependency for this included build's project.
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
        minSdk = 33
        // At or above Accrescent's minimum target SDK so that parsing this app's APK set succeeds
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
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
}

// For each variant, register a task which builds that variant's APK set
androidComponents.onVariants { variant ->
    val apkSetElements = configurations.consumable("${variant.name}ApkSetElements") {
        attributes.attribute(
            ApkSetAttr.ATTRIBUTE,
            objects.named(ApkSetAttr::class.java, ApkSetAttr.PRESENT),
        )
        attributes.attribute(
            BuildTypeAttr.ATTRIBUTE,
            objects.named(BuildTypeAttr::class, variant.buildType!!),
        )
        attributes.attribute(ApkSetNameAttr.ATTRIBUTE, apkSetName)
    }
    val taskName = "buildApkSet${variant.name.replaceFirstChar(Char::uppercase)}"

    val buildApkSetTask = tasks.register(taskName) {
        val bundle = variant.artifacts.get(SingleArtifact.BUNDLE)
        inputs.file(bundle)

        val apkSet = layout.buildDirectory.file(
            Paths
                .get(
                    "outputs",
                    "apkset",
                    variant.name,
                    "${project.name}-${variant.name}.apks",
                )
                .toString()
        )
        outputs.file(apkSet)

        doLast {
            val buildToolsDir = Paths.get(
                androidComponents.sdkComponents.sdkDirectory.get().toString(),
                SdkConstants.FD_BUILD_TOOLS,
                SdkConstants.CURRENT_BUILD_TOOLS_VERSION,
            )
            val buildToolInfo = BuildToolInfo.fromStandardDirectoryLayout(
                Revision.parseRevision(SdkConstants.CURRENT_BUILD_TOOLS_VERSION),
                buildToolsDir
            )
            val aapt2Path = buildToolInfo.getPath(BuildToolInfo.PathId.AAPT2)

            val signingConfiguration = SigningConfiguration
                .builder()
                .setSignerConfig(signingKey, signingCert)
                .build()

            BuildApksCommand.builder()
                .setBundlePath(bundle.get().asFile.toPath())
                .setOutputFile(apkSet.get().asFile.toPath())
                .setSigningConfiguration(signingConfiguration)
                .setAapt2Command(Aapt2Command.createFromExecutablePath(Paths.get((aapt2Path))))
                .setOverwriteOutput(true)
                .build()
                .execute()
        }
    }

    artifacts.add(apkSetElements.name, buildApkSetTask.map { it.outputs.files.singleFile })
}
