// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PositiveIntTest {
    @Test
    fun `new rejects negative value`() {
        val result = PositiveInt.new(-1)

        assertTrue(result.isNone())
    }

    @Test
    fun `new rejects zero`() {
        val result = PositiveInt.new(0)

        assertTrue(result.isNone())
    }

    @Test
    fun `new accepts positive value`() {
        val result = PositiveInt.new(1)

        assertTrue(result.isSome())
    }
}
