// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BytesTest {
    @Test
    fun `mutating the array passed to the constructor does not mutate the instance`() {
        val byteArray = "deadbeef".hexToByteArray()
        val bytes = Bytes(byteArray)

        byteArray[0] = 0

        assertArrayEquals("deadbeef".hexToByteArray(), bytes.copyToByteArray())
    }

    @Test
    fun `mutating an array from copyToByteArray does not mutate the instance`() {
        val bytes = Bytes("deadbeef".hexToByteArray())

        bytes.copyToByteArray()[0] = 0

        assertArrayEquals("deadbeef".hexToByteArray(), bytes.copyToByteArray())
    }

    @Test
    fun `instances with same contents are equal`() {
        val instance1 = Bytes("deadbeef".hexToByteArray())
        val instance2 = Bytes("deadbeef".hexToByteArray())

        assertEquals(instance1, instance2)
    }

    @Test
    fun `instances with same contents have same hashCode`() {
        val instance1 = Bytes("deadbeef".hexToByteArray())
        val instance2 = Bytes("deadbeef".hexToByteArray())

        assertEquals(instance1.hashCode(), instance2.hashCode())
    }
}
