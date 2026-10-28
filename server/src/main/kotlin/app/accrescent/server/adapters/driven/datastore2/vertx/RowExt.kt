// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import app.accrescent.server.core.Error
import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.causedBy
import app.accrescent.server.core.downcast
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreError
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.raise.Raise
import io.vertx.sqlclient.Row
import kotlin.reflect.KClass

/**
 * Gets a required row value.
 *
 * Raises [DataStoreError.IllegalState] if the fetched value is `null` or is not of type [T].
 *
 * @param T the type of value to get.
 * @return the value fetched from the database.
 */
context(_: Raise<DataStoreError>)
inline fun <reified T : Any> Row.requireValue(columnIndex: Int): T {
    return getSafeValue<T>(columnIndex).toEitherBind {
        DataStoreError.IllegalState().causedBy(UnexpectedNullColumn(columnIndex))
    }
}

/**
 * Gets a required row value of SQL type `integer` as a [NonNegativeInt].
 *
 * Raises [DataStoreError.IllegalState] if the fetched value is `null`, not of type [Int], or
 * negative.
 *
 * @return the value fetched from the database.
 */
context(_: Raise<DataStoreError>)
fun Row.requireNonNegativeInt(columnIndex: Int): NonNegativeInt {
    val value = requireValue<Int>(columnIndex)

    return NonNegativeInt.new(value).toEitherBind {
        DataStoreError.IllegalState()
            .causedBy(ColumnValueOutOfRange(columnIndex, value, NonNegativeInt::class))
    }
}

/**
 * Gets an optional row value.
 *
 * Raises [DataStoreError.IllegalState] if the fetched value is not of type [T].
 *
 * @param T the type of value to get.
 * @return [None] if the database returns `null`, or otherwise the value fetched from the database.
 */
context(_: Raise<DataStoreError>)
inline fun <reified T : Any> Row.getSafeValue(columnIndex: Int): Option<T> {
    val value = getValue(columnIndex) ?: return None

    return value.downcast<T>()
        .map(::Some)
        .toEitherBind {
            DataStoreError.IllegalState()
                .causedBy(UnexpectedColumnType(columnIndex, T::class, value::class))
        }
}

/**
 * A row value was out of the range of the type it was converted to.
 *
 * @param columnIndex the index of the column the value was fetched from.
 * @param value the value fetched from the database.
 * @param target the type the value was converted to.
 */
class ColumnValueOutOfRange(columnIndex: Int, value: Int, target: KClass<*>) : Error() {
    override val message =
        "expected column $columnIndex to be in the range of type ${target.qualifiedName}, but " +
                "it was $value"
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
