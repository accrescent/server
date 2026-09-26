// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.encoding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Base62Test {
    @Test
    fun `encodes leading zeroes`() {
        val bytes = "00deadbeef".hexToByteArray()

        val encoded = Base62.encode(bytes)

        assertEquals("044pZgF", encoded)
    }
}
