// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.appstore

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppShortDescriptionTest {
    @Test
    fun `fromString rejects empty value`() {
        val result = AppShortDescription.fromString("")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString accepts value 1 codepoint long`() {
        val result = AppShortDescription.fromString("a")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts value 80 codepoints long`() {
        val result = AppShortDescription.fromString("a".repeat(80))

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString rejects value longer than 80 codepoints`() {
        val result = AppShortDescription.fromString("a".repeat(81))

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString counts supplementary codepoints as a single character`() {
        // U+1F600 GRINNING FACE is a supplementary character represented as a UTF-16 surrogate
        // pair, i.e. two chars but one code point
        val rawShortDescription = "😀".repeat(80)
        assertEquals(160, rawShortDescription.length)
        assertEquals(80, rawShortDescription.codePointCount(0, rawShortDescription.length))

        val shortDescription = AppShortDescription.fromString(rawShortDescription)

        assertTrue(shortDescription.isSome())
    }

    @Test
    fun `value returns original string`() {
        val rawShortDescription = "Secure texting for everyone"
        val shortDescription = AppShortDescription.fromString(rawShortDescription).unwrap()

        assertEquals(rawShortDescription, shortDescription.value)
    }

    @Test
    fun `instances with same value are equal`() {
        val instance1 = AppShortDescription.fromString("Secure texting for everyone").unwrap()
        val instance2 = AppShortDescription.fromString("Secure texting for everyone").unwrap()

        assertEquals(instance1, instance2)
    }
}
