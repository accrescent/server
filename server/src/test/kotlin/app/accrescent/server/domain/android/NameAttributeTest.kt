// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NameAttributeTest {
    @Test
    fun `fromString accepts value 1024 codepoints long`() {
        val rawName = "a".repeat(1024)
        assertEquals(1024, rawName.codePointCount(0, rawName.length))

        val versionName = NameAttribute.fromString(rawName)

        assertTrue(versionName.isSome())
    }

    @Test
    fun `fromString rejects value longer than 1024 codepoints`() {
        val rawName = "a".repeat(1025)
        assertEquals(1025, rawName.codePointCount(0, rawName.length))

        val versionName = NameAttribute.fromString(rawName)

        assertTrue(versionName.isNone())
    }

    @Test
    fun `fromString counts supplementary codepoints as a single character`() {
        // U+1F600 GRINNING FACE is a supplementary character represented as a UTF-16 surrogate
        // pair, i.e. two chars but one codepoint
        val rawName = "😀".repeat(1024)
        assertEquals(2048, rawName.length)
        assertEquals(1024, rawName.codePointCount(0, rawName.length))

        val versionName = NameAttribute.fromString(rawName)

        assertTrue(versionName.isSome())
    }

    @Test
    fun `value returns original string`() {
        val rawName = "android.permission.INTERNET"
        val versionName = NameAttribute.fromString(rawName).unwrap()

        assertEquals(rawName, versionName.value)
    }

    @Test
    fun `instances with same value are equal`() {
        val rawName = "android.permission.QUERY_ALL_PACKAGES"
        val instance1 = NameAttribute.fromString(rawName).unwrap()
        val instance2 = NameAttribute.fromString(rawName).unwrap()

        assertEquals(instance1, instance2)
    }
}
