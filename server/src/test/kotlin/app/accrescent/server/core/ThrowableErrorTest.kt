// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThrowableErrorTest {
    @Test
    fun `toString includes full Throwable cause chain`() {
        val originalThrowable = Throwable("original error")
        val causedThrowable = Throwable("caused error", originalThrowable)
        val error = ThrowableError(causedThrowable)

        val result = error.toString()

        assertEquals("caused error: original error", result)
    }
}
