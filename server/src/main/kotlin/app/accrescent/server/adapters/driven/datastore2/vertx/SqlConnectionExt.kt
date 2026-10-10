// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import app.accrescent.server.core.Error
import app.accrescent.server.core.causedBy
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreError
import arrow.core.None
import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.raise.Raise
import arrow.core.raise.catch
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

/**
 * Executes a query which should return at most one row.
 *
 * Raises [DataStoreError.IllegalState] if any number of rows are returned other than zero or one.
 *
 * @param sql the SQL query to execute.
 * @param params the query's parameters.
 * @param mapError converter from exceptions thrown during execution to [DataStoreError].
 * @return the row returned by the query, or [None] if it returned no rows.
 */
context(raise: Raise<DataStoreError>)
suspend fun SqlConnection.executeOptionalQuery(
    sql: String,
    params: Tuple,
    mapError: (Throwable) -> DataStoreError,
): Option<Row> {
    val rows = catch({ preparedQuery(sql).execute(params).coAwait() }) {
        raise.raise(mapError(it))
    }
    if (rows.size() != 0 && rows.size() != 1) {
        raise.raise(DataStoreError.IllegalState().causedBy(UnexpectedResultSize(rows.size())))
    }

    return rows.firstOrNone()
}

/**
 * Executes a query which should affect exactly one row.
 *
 * Raises [DataStoreError.IllegalState] if the statement affects any other number of rows.
 *
 * @param sql the SQL statement to execute.
 * @param params the statement's parameters.
 * @param mapError converter from exceptions thrown during execution to [DataStoreError].
 */
context(raise: Raise<DataStoreError>)
suspend fun SqlConnection.executeSingleUpdate(
    sql: String,
    params: Tuple,
    mapError: (Throwable) -> DataStoreError,
) {
    val rows = catch({ preparedQuery(sql).execute(params).coAwait() }) {
        raise.raise(mapError(it))
    }
    if (rows.rowCount() != 1) {
        raise.raise(DataStoreError.IllegalState().causedBy(UnexpectedRowCount(1, rows.rowCount())))
    }
}

/**
 * A statement affected a different number of rows than expected.
 *
 * @param expected the number of rows the statement was expected to affect.
 * @param actual the number of rows the statement actually affected.
 */
private class UnexpectedRowCount(expected: Int, actual: Int) : Error() {
    override val message = "expected statement to affect $expected rows, but it affected $actual"
}

/**
 * A query which should return at most one row reported returning a different number of rows.
 *
 * @param actual the number of rows the query reported returning.
 */
private class UnexpectedResultSize(actual: Int) : Error() {
    override val message = "expected query to return 0 or 1 rows, but it returned $actual"
}
