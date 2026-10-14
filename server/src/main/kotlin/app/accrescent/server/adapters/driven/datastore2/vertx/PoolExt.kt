// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import app.accrescent.server.domain.ports.driven.datastore2.DataStoreError
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreResult
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.either
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gets a connection from this pool.
 *
 * If canceled while waiting, the connection is closed as soon as the pool hands it over so that it
 * isn't leaked from the pool.
 */
suspend fun Pool.awaitConnection(): SqlConnection {
    val future = connection
    return try {
        future.coAwait()
    } catch (e: CancellationException) {
        future.onSuccess { it.close() }
        throw e
    }
}

/**
 * Runs the given lambda in the context of a transaction on a connection from this pool.
 *
 * The transaction is committed if [block] returns normally. If [block] raises or throws, the
 * transaction is rolled back.
 *
 * The transaction runs at the isolation level of the connection it runs on, so callers which
 * require a particular isolation level must configure the pool's connections accordingly.
 *
 * @param mapError converter from exceptions thrown during execution to [DataStoreError].
 * @param block a lambda to run in the context of the transaction.
 * @return the value returned by [block] wrapped in the [DataStoreResult] of executing the
 * transaction.
 */
suspend fun <T, E> Pool.runInTransaction(
    mapError: (Throwable) -> DataStoreError,
    block: suspend Raise<E>.(SqlConnection) -> T,
): DataStoreResult<Either<E, T>> = either {
    val connection = catch({ awaitConnection() }) { raise(mapError(it)) }
    try {
        val transaction = catch({ connection.begin().coAwait() }) { raise(mapError(it)) }
        val result = either { block(connection) }
        if (result.isRight()) {
            // Don't commit for a caller that was canceled while block ran
            currentCoroutineContext().ensureActive()
            catch({ transaction.commit().coAwait() }) { raise(mapError(it)) }
        }
        result
    } finally {
        // Closing the connection rolls back the transaction if it wasn't committed. There's
        // nothing to do if closing fails, so don't wait for it.
        connection.close()
    }
}
