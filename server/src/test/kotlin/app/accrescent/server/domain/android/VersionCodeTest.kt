// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCodeTest {
    @Test
    fun `fromInt returns None for minimum valid value minus 1`() {
        val result = VersionCode.fromInt(0)

        assertTrue(result.isNone())
    }

    @Test
    fun `fromInt returns None for maximum valid value plus one`() {
        val result = VersionCode.fromInt(2100000001)

        assertTrue(result.isNone())
    }

    @Test
    fun `fromInt returns successfully for minimum valid value`() {
        val result = VersionCode.fromInt(1)

        assertTrue(result.isSome())
    }

    @Test
    fun `fromInt returns successfully for maximum valid value`() {
        val result = VersionCode.fromInt(2100000000)

        assertTrue(result.isSome())
    }

    @Test
    fun `fromInt and value round-trip data`() {
        val rawCode = 1478417331 // randomly generated
        val versionCode = VersionCode.fromInt(rawCode).unwrap()

        assertEquals(rawCode, versionCode.value)
    }

    @Test
    fun `instances with same value are equal`() {
        val rawCode = 986280425 // randomly generated
        val instance1 = VersionCode.fromInt(rawCode).unwrap()
        val instance2 = VersionCode.fromInt(rawCode).unwrap()

        assertEquals(instance1, instance2)
    }
}
