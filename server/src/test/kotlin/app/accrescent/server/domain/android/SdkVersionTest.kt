// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SdkVersionTest {
    @Test
    fun `fromInt returns None for zero`() {
        val result = SdkVersion.fromInt(-1)

        assertTrue(result.isNone())
    }

    @Test
    fun `fromInt returns successfully for minimum valid value`() {
        val result = SdkVersion.fromInt(1)

        assertTrue(result.isSome())
    }

    @Test
    fun `fromInt returns successfully for maximum valid value`() {
        val result = SdkVersion.fromInt(Int.MAX_VALUE)

        assertTrue(result.isSome())
    }

    @Test
    fun `fromInt and value round-trip data`() {
        val rawValue = 24
        val sdkVersion = SdkVersion.fromInt(rawValue).unwrap()

        assertEquals(rawValue, sdkVersion.value)
    }

    @Test
    fun `instances with same value are equal`() {
        val rawValue = 36
        val instance1 = SdkVersion.fromInt(rawValue).unwrap()
        val instance2 = SdkVersion.fromInt(rawValue).unwrap()

        assertEquals(instance1, instance2)
    }

    @Test
    fun `instance with greater value compares greater`() {
        val lesser = SdkVersion.fromInt(24).unwrap()
        val greater = SdkVersion.fromInt(25).unwrap()

        assertTrue(greater > lesser)
    }
}
