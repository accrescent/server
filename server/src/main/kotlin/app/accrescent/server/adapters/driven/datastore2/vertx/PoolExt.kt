// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlConnection
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
