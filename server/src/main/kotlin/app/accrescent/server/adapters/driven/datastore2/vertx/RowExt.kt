// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import app.accrescent.server.core.Error
import app.accrescent.server.core.causedBy
import app.accrescent.server.core.downcast
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreError
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreResult
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.flatMap
import arrow.core.right
import io.vertx.sqlclient.Row
import kotlin.reflect.KClass

/**
 * Gets a required row value.
 *
 * @param T the type of value to get.
 * @return [DataStoreError.IllegalState] if the fetched value is `null` or is not of type [T], or
 * otherwise the value fetched from the database.
 */
inline fun <reified T : Any> Row.requireValue(columnIndex: Int): DataStoreResult<T> {
    return getSafeValue<T>(columnIndex).flatMap { value ->
        value.toEither {
            DataStoreError.IllegalState().causedBy(UnexpectedNullColumn(columnIndex))
        }
    }
}

/**
 * Gets an optional row value.
 *
 * @param T the type of value to get.
 * @return [None] if the database returns `null`, [DataStoreError.IllegalState] if the fetched value
 * is not of type [T], or otherwise the value fetched from the database.
 */
inline fun <reified T : Any> Row.getSafeValue(columnIndex: Int): DataStoreResult<Option<T>> {
    val value = getValue(columnIndex) ?: return None.right()

    return value.downcast<T>()
        .map(::Some)
        .toEither {
            DataStoreError.IllegalState()
                .causedBy(UnexpectedColumnType(columnIndex, T::class, value::class))
        }
}

/**
 * A row value was of a different type than expected.
 *
 * @param columnIndex the index of the column the value was fetched from.
 * @param expected the type the value was expected to be.
 * @param actual the type the value actually was.
 */
class UnexpectedColumnType(columnIndex: Int, expected: KClass<*>, actual: KClass<*>) : Error() {
    override val message =
        "expected column $columnIndex to be of type ${expected.qualifiedName}, but it was of type " +
                "${actual.qualifiedName}"
}

/**
 * A required row value was `null`.
 *
 * @param columnIndex the index of the column the value was fetched from.
 */
class UnexpectedNullColumn(columnIndex: Int) : Error() {
    override val message = "expected column $columnIndex to be non-null, but it was null"
}
