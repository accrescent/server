// SPDX-FileCopyrightText: © 2023 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

rootProject.name = "parcelo"

include("detekt-rules", "parcelo")

includeBuild("testdata")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}
pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
