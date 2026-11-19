// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Executors

private val VIRTUAL_THREAD_DISPATCHER =
    Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

/**
 * Runs a blocking lambda on a new virtual thread, suspending until it completes.
 *
 * If the calling coroutine is canceled while [block] is running, the virtual thread will be
 * interrupted.
 *
 * @param block the blocking code to run.
 * @return the value returned by [block].
 */
suspend fun <T> runOnVirtualThread(block: () -> T): T {
    return runInterruptible(VIRTUAL_THREAD_DISPATCHER, block)
}
