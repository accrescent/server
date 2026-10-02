// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.config

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ShutdownTimeoutTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 60])
    fun `parse accepts seconds in range`(seconds: Int) {
        val result = ShutdownTimeout.parse("${seconds}s")

        assertTrue(result.isSome())
    }

    @Test
    fun `parse rejects seconds out of range`() {
        val result = ShutdownTimeout.parse("61s")

        assertTrue(result.isNone())
    }

    @Test
    fun `parse rejects leading zeroes`() {
        val result = ShutdownTimeout.parse("01s")

        assertTrue(result.isNone())
    }

    @Test
    fun `parse rejects empty string`() {
        val result = ShutdownTimeout.parse("")

        assertTrue(result.isNone())
    }
}
