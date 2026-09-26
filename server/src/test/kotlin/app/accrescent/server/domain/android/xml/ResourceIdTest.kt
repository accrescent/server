// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResourceIdTest {
    @Test
    fun `instances with the same value are equal`() {
        val rawValue = 0x3378d351u
        val instance1 = ResourceId(rawValue)
        val instance2 = ResourceId(rawValue)

        assertEquals(instance1, instance2)
    }

    @Test
    fun `instances with the same value have the same hash code`() {
        val rawValue = 0x779bd790u
        val instance1 = ResourceId(rawValue)
        val instance2 = ResourceId(rawValue)

        assertEquals(instance1.hashCode(), instance2.hashCode())
    }
}
