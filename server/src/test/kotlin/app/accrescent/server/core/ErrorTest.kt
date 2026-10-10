// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ErrorTest {
    @Test
    fun `toString returns message for error without cause`() {
        val error = TestError("error")

        val result = error.toString()

        assertEquals("error", result)
    }

    @Test
    fun `toString includes full cause chain`() {
        val inner = TestError("inner")
        val middle = TestError("middle").causedBy(inner)
        val outer = TestError("outer").causedBy(middle)

        val result = outer.toString()

        assertEquals("outer: middle: inner", result)
    }

    private companion object {
        private class TestError(override val message: String) : Error()
    }
}
