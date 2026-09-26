// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationIdTest {
    @Test
    fun `fromString rejects value with only one segment`() {
        val appId = ApplicationId.fromString("example")

        assertTrue(appId.isNone())
    }

    @Test
    fun `fromString rejects value with segment starting with non-letter`() {
        val appId = ApplicationId.fromString("com.3xample")

        assertTrue(appId.isNone())
    }

    @Test
    fun `fromString accepts all valid characters`() {
        val appId =
            ApplicationId.fromString("bcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.a")

        assertTrue(appId.isSome())
    }

    @Test
    fun `fromString accepts value 128 characters long`() {
        val rawId = "a." + "b".repeat(126)
        assertEquals(128, rawId.length)

        val appId = ApplicationId.fromString(rawId)

        assertTrue(appId.isSome())
    }

    @Test
    fun `fromString rejects value longer than 128 characters`() {
        val rawId = "a." + "b".repeat(127)
        assertEquals(129, rawId.length)

        val appId = ApplicationId.fromString(rawId)

        assertTrue(appId.isNone())
    }

    @Test
    fun `value returns original string`() {
        val rawId = "com.example.myapp"
        val appId = ApplicationId.fromString(rawId).unwrap()

        assertEquals(rawId, appId.value)
    }

    @Test
    fun `instances with same value are equal`() {
        val rawId = "com.example.myapp"
        val instance1 = ApplicationId.fromString(rawId).unwrap()
        val instance2 = ApplicationId.fromString(rawId).unwrap()

        assertEquals(instance1, instance2)
    }
}
