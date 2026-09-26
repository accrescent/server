// SPDX-FileCopyrightText: © 2025 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

import app.accrescent.server.build.ApkAttr
import app.accrescent.server.build.ApkSetAttr
import app.accrescent.server.build.ApkSetNameAttr
import com.android.build.api.attributes.BuildTypeAttr
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    id("app.accrescent.server.build.apk-attributes")
    id("app.accrescent.server.build.apk-set-attributes")
}

val testApkSets = configurations.create("testApkSets") {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes.attribute(ApkSetAttr.ATTRIBUTE, objects.named(ApkSetAttr::class, ApkSetAttr.PRESENT))
    attributes.attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class, "release"))
}

val testApks = configurations.create("testApks") {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes.attribute(ApkAttr.ATTRIBUTE, objects.named(ApkAttr::class, ApkAttr.PRESENT))
    attributes.attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class, "release"))
}

dependencies {
    implementation(libs.apksig)
    implementation(libs.appstore.api.grpc.kotlin)
    implementation(libs.arrow.core)
    implementation(libs.binary.resources)
    implementation(libs.bundletool)
    implementation(libs.console.api.grpc.kotlin)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.h2)
    implementation(libs.jena.iri3986)
    implementation(libs.postgresql.jdbc)
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)
    implementation(libs.vertx.grpcio.server)
    implementation(libs.vertx.lang.kotlin.coroutines)
    runtimeOnly(libs.slf4j.nop)
    detektPlugins(project(":detekt-rules"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.commons.compress)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.rest.assured)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
    testApkSets("app.accrescent.server.testdata:android-app-low-target-sdk")
    testApkSets("app.accrescent.server.testdata:android-app-valid")
    testApks("app.accrescent.server.testdata:android-app-signing")
}

group = "app.accrescent.server"
version = "0.16.0"

application {
    mainClass = "app.accrescent.server.MainKt"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
}

// Build the test data and expose each artifact's path to tests as a system property: APK sets as
// "testdata.apkset.<name>.path" (where <name> is the APK set's ApkSetNameAttr value), and
// individual APKs as "testdata.apk.<name>.path" (where <name> is the APK's file name without
// extension).
tasks.test {
    inputs.files(testApkSets)
    inputs.files(testApks)

    doFirst {
        testApkSets.incoming.artifacts.forEach { artifact ->
            val name = artifact.variant.attributes.getAttribute(ApkSetNameAttr.ATTRIBUTE)!!
            systemProperty("testdata.apkset.$name.path", artifact.file.absolutePath)
        }
        testApks.incoming.artifacts.forEach { artifact ->
            systemProperty("testdata.apk.${artifact.file.nameWithoutExtension}.path", artifact.file.absolutePath)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        javaParameters = true
    }
}

protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("kotlin")
            }
        }
    }
}

// Only our custom rules from the detekt-rules project run; detekt's bundled rule sets are disabled.
// The unsafe-JDBC-method rule needs type resolution, so it only runs in the detektMain/detektTest
// tasks (which provide a compile classpath), not the classpath-less plain `detekt` task.
detekt {
    disableDefaultRuleSets = true
}
tasks.register("lint") {
    dependsOn(tasks.named("detektMain"))
    dependsOn(tasks.named("detektTest"))
}
