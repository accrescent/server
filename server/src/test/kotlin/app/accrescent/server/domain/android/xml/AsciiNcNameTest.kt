// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsciiNcNameTest {
    @Test
    fun `fromString rejects value containing colon`() {
        val name = AsciiNcName.fromString("example:name")

        assertTrue(name.isNone())
    }

    @Test
    fun `fromString rejects value containing non-ASCII character`() {
        val name = AsciiNcName.fromString("exämple")

        assertTrue(name.isNone())
    }

    @Test
    fun `fromString accepts all valid characters`() {
        val name =
            AsciiNcName.fromString("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-")

        assertTrue(name.isSome())
    }

    @Test
    fun `value returns original string`() {
        val rawName = "example-name"
        val name = AsciiNcName.fromString(rawName).unwrap()

        assertEquals(rawName, name.value)
    }

    @Test
    fun `instances with same value are equal`() {
        val rawName = "example-name"
        val instance1 = AsciiNcName.fromString(rawName).unwrap()
        val instance2 = AsciiNcName.fromString(rawName).unwrap()

        assertEquals(instance1, instance2)
    }
}
