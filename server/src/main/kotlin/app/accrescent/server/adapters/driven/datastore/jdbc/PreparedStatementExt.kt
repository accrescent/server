// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore.jdbc

import app.accrescent.server.domain.ports.driven.datastore.DataStoreError
import app.accrescent.server.domain.ports.driven.datastore.DataStoreResult
import arrow.core.Either
import java.sql.PreparedStatement

fun PreparedStatement.executeSingleUpdate(): DataStoreResult<Unit> {
    @Suppress("DirectPreparedStatementExecuteUpdate")
    return when (executeUpdate()) {
        0 -> Either.Left(DataStoreError.EntityNotFound)
        1 -> Either.Right(Unit)
        else -> Either.Left(DataStoreError.IllegalState)
    }
}

fun PreparedStatement.executeMultiUpdate(): DataStoreResult<Unit> {
    @Suppress("DirectPreparedStatementExecuteUpdate")
    return if (executeUpdate() >= 0) {
        Either.Right(Unit)
    } else {
        Either.Left(DataStoreError.IllegalState)
    }
}
