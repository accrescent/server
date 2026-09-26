// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(ExperimentalContracts::class)

package app.accrescent.server.core

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.raise.Raise
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Unwraps the inner [Some] value if it exists.
 *
 * @return the value wrapped by this [Some] value.
 * @throws IllegalStateException if this value is [None].
 */
fun <T> Option<T>.unwrap(): T {
    return when (this) {
        is None -> throw IllegalStateException("unwrap() called on a None value")
        is Some -> value
    }
}

/**
 * Unwraps the inner [Some] value of this option, raising an error otherwise.
 *
 * If this option is [None], is it converted to an error of type [E] via [ifEmpty] and raised.
 *
 * Shorthand for `.toEither(ifEmpty).bind()`.
 *
 * @return the inner [Some] value if this value is [Some].
 */
context(raise: Raise<E>)
inline fun <T, E> Option<T>.toEitherBind(ifEmpty: () -> E): T {
    contract { callsInPlace(ifEmpty, InvocationKind.AT_MOST_ONCE) }

    return when (this) {
        is None -> raise.raise(ifEmpty())
        is Some -> value
    }
}
