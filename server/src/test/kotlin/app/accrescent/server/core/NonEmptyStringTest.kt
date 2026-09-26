// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NonEmptyStringTest {
    @Test
    fun `fromString returns None for empty string`() {
        val result = NonEmptyString.fromString("")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString returns successfully for non-empty string`() {
        val result = NonEmptyString.fromString("a")

        assertTrue(result.isSome())
    }

    @Test
    fun `value returns original string`() {
        val rawString = "Hello, world!"
        val nonEmptyString = NonEmptyString.fromString(rawString).unwrap()

        assertEquals(rawString, nonEmptyString.value)
    }

    @Test
    fun `instances with same value are equal`() {
        val instance1 = NonEmptyString.fromString("Hello, world!").unwrap()
        val instance2 = NonEmptyString.fromString("Hello, world!").unwrap()

        assertEquals(instance1, instance2)
    }
}
