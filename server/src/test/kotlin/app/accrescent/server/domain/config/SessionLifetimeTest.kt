// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.config

import arrow.core.some
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.hours

class SessionLifetimeTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 8760])
    fun `parse accepts hours in range`(hours: Int) {
        val result = SessionLifetime.parse("${hours}h")

        assertEquals(hours.hours.some(), result.map { it.value })
    }

    @ParameterizedTest
    @ValueSource(strings = ["0h", "8761h"])
    fun `parse rejects hours out of range`(value: String) {
        val result = SessionLifetime.parse(value)

        assertTrue(result.isNone())
    }

    @Test
    fun `parse rejects leading zeroes`() {
        val result = SessionLifetime.parse("01h")

        assertTrue(result.isNone())
    }

    @Test
    fun `parse rejects empty string`() {
        val result = SessionLifetime.parse("")

        assertTrue(result.isNone())
    }
}
