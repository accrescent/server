// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server

import org.junit.jupiter.api.Tag

/**
 * Marks a test class or method as an integration test, i.e., one which requires external services,
 * authentication, and/or extra configuration.
 *
 * Integration tests are run only via the `integrationTest` Gradle task.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
annotation class IntegrationTest

/**
 * Gets the value of an integration test property.
 *
 * @param name the unqualified name of the integration test property to get.
 * @throws IllegalStateException if the requested property is not set.
 */
fun integrationTestProperty(name: String): String =
    System.getProperty("integrationTest.$name")
        ?: error("integration test property $name must be set to run integration tests")
