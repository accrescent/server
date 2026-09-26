// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NonNegativeLongTest {
    @Test
    fun `new rejects negative value`() {
        val result = NonNegativeLong.new(-1)

        assertTrue(result.isNone())
    }

    @Test
    fun `new accepts zero`() {
        val result = NonNegativeLong.new(0)

        assertTrue(result.isSome())
    }

    @Test
    fun `new accepts positive value`() {
        val result = NonNegativeLong.new(1)

        assertTrue(result.isSome())
    }
}
