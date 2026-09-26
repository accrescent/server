// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FalliblyCloseableTest {
    @Test
    fun `use returns block value when both succeed`() {
        val resource = FalliblyCloseable<String> { Unit.right() }

        val result = resource.use<Int, Nothing, String, _> { 42 }

        assertEquals(42.right(), result)
    }

    @Test
    fun `use returns block error when block fails and close succeeds`() {
        val resource = FalliblyCloseable<String> { Unit.right() }

        val result = resource.use<Int, Int, String, _> { raise(7) }

        assertEquals(UseError.Block(7).left(), result)
    }

    @Test
    fun `use returns close error when block succeeds and close fails`() {
        val resource = FalliblyCloseable { "close failure".left() }

        val result = resource.use<Int, Nothing, String, _> { 42 }

        assertEquals(UseError.Close("close failure").left(), result)
    }

    @Test
    fun `use returns both errors when both fail`() {
        val resource = FalliblyCloseable { "close failure".left() }

        val result = resource.use<Int, Int, String, _> { raise(7) }

        assertEquals(UseError.Both(7, "close failure").left(), result)
    }

    @Test
    fun `use calls close when block throws`() {
        var closeCalled = false
        val resource = FalliblyCloseable<String> {
            closeCalled = true
            Unit.right()
        }

        runCatching { resource.use<Int, Nothing, String, _> { throw Exception("block threw") }.unwrap() }

        assertTrue(closeCalled)
    }

    @Test
    fun `use propagates exception when block throws`() {
        val resource = FalliblyCloseable { "close failure".left() }

        assertThrows(Exception::class.java) {
            resource.use<Nothing, Nothing, String, _> { throw Exception("block threw") }.unwrap()
        }
    }

    @Test
    fun `use adds close throwable as suppressed when both block and close throw`() {
        val closeException = Exception("close threw")
        val resource = FalliblyCloseable<Nothing> { throw closeException }

        val blockException = assertThrows(Exception::class.java) {
            resource.use<Nothing, Nothing, Nothing, _> { throw Exception("block threw") }.unwrap()
        }

        assertTrue(closeException in blockException.suppressed)
    }
}
