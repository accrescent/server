// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore.jdbc

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.intoULong
import app.accrescent.server.core.okOrElse
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.domain.ports.driven.datastore.DataStoreError
import app.accrescent.server.domain.ports.driven.datastore.DataStoreResult
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.raise.either
import arrow.core.toOption
import java.sql.ResultSet

fun ResultSet.getSafeBoolean(columnIndex: Int): Option<Boolean> {
    @Suppress("UnsafeJdbcResultSetMethodCall")
    val rawValue = getBoolean(columnIndex)
    return if (wasNull()) {
        None
    } else {
        Some(rawValue)
    }
}

fun ResultSet.getSafeBoolean(columnLabel: String): Option<Boolean> {
    @Suppress("UnsafeJdbcResultSetMethodCall")
    val rawValue = getBoolean(columnLabel)
    return if (wasNull()) {
        None
    } else {
        Some(rawValue)
    }
}

fun ResultSet.getSafeBytes(columnLabel: String): Option<ByteArray> {
    @Suppress("UnsafeJdbcResultSetMethodCall")
    return getBytes(columnLabel).toOption()
}

fun ResultSet.getSafeInt(columnLabel: String): Option<Int> {
    @Suppress("UnsafeJdbcResultSetMethodCall")
    val rawValue = getInt(columnLabel)
    return if (wasNull()) {
        None
    } else {
        Some(rawValue)
    }
}

fun ResultSet.getSafeLong(columnIndex: Int): Option<Long> {
    @Suppress("UnsafeJdbcResultSetMethodCall")
    val rawValue = getLong(columnIndex)
    return if (wasNull()) {
        None
    } else {
        Some(rawValue)
    }
}

fun ResultSet.getSafeLong(columnLabel: String): Option<Long> {
    @Suppress("UnsafeJdbcResultSetMethodCall")
    val rawValue = getLong(columnLabel)
    return if (wasNull()) {
        None
    } else {
        Some(rawValue)
    }
}

inline fun <reified T : Any> ResultSet.getSafeObject(columnLabel: String): Option<T> {
    @Suppress("UnsafeJdbcResultSetMethodCall")
    val rawValue = getObject(columnLabel, T::class.java)
    // Although rawValue is boxed (and thus nullable on the JVM), the getObject signature we're
    // using doesn't explicitly state that it will return null if a SQL NULL is encountered. Thus,
    // to be safe, we use the wasNull() strategy here that we use for unboxed primitives elsewhere.
    return if (wasNull()) {
        None
    } else {
        Some(rawValue)
    }
}

fun ResultSet.getSafeString(columnLabel: String): Option<String> {
    @Suppress("UnsafeJdbcResultSetMethodCall")
    return getString(columnLabel).toOption()
}

fun ResultSet.requireBoolean(columnIndex: Int): DataStoreResult<Boolean> {
    return getSafeBoolean(columnIndex).toEither { DataStoreError.IllegalState }
}

fun ResultSet.requireBoolean(columnLabel: String): DataStoreResult<Boolean> {
    return getSafeBoolean(columnLabel).toEither { DataStoreError.IllegalState }
}

fun ResultSet.requireBytes(columnLabel: String): DataStoreResult<ByteArray> {
    return getSafeBytes(columnLabel).toEither { DataStoreError.IllegalState }
}

fun ResultSet.requireInt(columnLabel: String): DataStoreResult<Int> {
    return getSafeInt(columnLabel).toEither { DataStoreError.IllegalState }
}

fun ResultSet.requireLong(columnLabel: String): DataStoreResult<Long> {
    return getSafeLong(columnLabel).toEither { DataStoreError.IllegalState }
}

inline fun <reified T : Any> ResultSet.requireObject(columnLabel: String): DataStoreResult<T> {
    return getSafeObject<T>(columnLabel).toEither { DataStoreError.IllegalState }
}

fun ResultSet.requireString(columnLabel: String): DataStoreResult<String> {
    return getSafeString(columnLabel).toEither { DataStoreError.IllegalState }
}

fun ResultSet.getSelectExistsResult(): DataStoreResult<Boolean> = either {
    next()
        .okOrElse { DataStoreError.IllegalState }
        .bind()
    getSafeBoolean(1)
        .toEitherBind { DataStoreError.IllegalState }
}

/**
 * Reads the single non-negative count returned by a `SELECT COUNT(...)` query.
 */
fun ResultSet.getSelectCountResult(): DataStoreResult<ULong> = either {
    next()
        .okOrElse { DataStoreError.IllegalState }
        .bind()
    getSafeLong(1)
        .toEitherBind { DataStoreError.IllegalState }
        .intoULong()
        .bindMapLeft { DataStoreError.IllegalState }
}
