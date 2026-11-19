// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class VirtualThreadsTest {
    @Test
    suspend fun `runOnVirtualThread returns the value returned by block`() {
        val result = runOnVirtualThread { 42 }

        assertEquals(42, result)
    }

    @Test
    suspend fun `runOnVirtualThread runs block on a virtual thread`() {
        val isVirtual = runOnVirtualThread { Thread.currentThread().isVirtual }

        assertTrue(isVirtual)
    }

    @Test
    suspend fun `runOnVirtualThread runs each block on a new thread`() {
        val first = runOnVirtualThread { Thread.currentThread() }
        val second = runOnVirtualThread { Thread.currentThread() }

        assertNotSame(first, second)
    }

    @Test
    suspend fun `runOnVirtualThread interrupts block when the calling coroutine is canceled`() {
        val started = CompletableDeferred<Unit>()
        var interrupted = false

        withTimeout(5.seconds) {
            val job = launch {
                runOnVirtualThread {
                    started.complete(Unit)
                    try {
                        Thread.sleep(Long.MAX_VALUE)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }

            started.await()
            job.cancelAndJoin()
        }

        assertTrue(interrupted)
    }
}
