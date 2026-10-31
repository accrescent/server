// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.appstore

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppNameTest {
    @Test
    fun `fromString rejects empty value`() {
        val result = AppName.fromString("")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString accepts value 1 code point long`() {
        val result = AppName.fromString("a")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts value 30 codepoints long`() {
        val result = AppName.fromString("a".repeat(30))

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString rejects value longer than 30 codepoints`() {
        val result = AppName.fromString("a".repeat(31))

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString counts supplementary codepoints as a single character`() {
        // U+1F600 GRINNING FACE is a supplementary character represented as a UTF-16 surrogate
        // pair, i.e. two chars but one code point
        val rawName = "😀".repeat(30)
        assertEquals(60, rawName.length)
        assertEquals(30, rawName.codePointCount(0, rawName.length))

        val name = AppName.fromString(rawName)

        assertTrue(name.isSome())
    }

    @Test
    fun `value returns original string`() {
        val rawName = "SecureChat - Secure Texting"
        val name = AppName.fromString(rawName).unwrap()

        assertEquals(rawName, name.value)
    }

    @Test
    fun `instances with same value are equal`() {
        val instance1 = AppName.fromString("SecureChat").unwrap()
        val instance2 = AppName.fromString("SecureChat").unwrap()

        assertEquals(instance1, instance2)
    }
}
