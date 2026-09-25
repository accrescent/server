// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TcpPortTest {
    @Test
    fun `new rejects zero`() {
        val result = TcpPort.new(0u)

        assertTrue(result.isNone())
    }
}
