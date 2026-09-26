// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(ExperimentalContracts::class)

package app.accrescent.server.core

import arrow.core.Either
import arrow.core.raise.Raise
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

fun <A, B> Either<A, B>.unwrap(): B {
    return when (this) {
        is Either.Left ->
            throw IllegalStateException("unwrap() called on an Either.Left value: $value")

        is Either.Right -> value
    }
}

fun <A, B> Either<A, B>.unwrapErr(): A {
    return when (this) {
        is Either.Left -> value
        is Either.Right -> throw IllegalStateException("unwrapErr() called on Either.Right value: $value")
    }
}

fun <A, B, C> Either<A, Either<B, C>>.unwrap2(): C = unwrap().unwrap()

/**
 * Binds this [Either], or maps its error with [f] and raises the result (see e.g. `toServerError`).
 */
context(raise: Raise<F>)
inline fun <E, F, T> Either<E, T>.bindMapLeft(f: (E) -> F): T {
    contract { callsInPlace(f, InvocationKind.AT_MOST_ONCE) }

    return when (this) {
        is Either.Left -> raise.raise(f(value))
        is Either.Right -> value
    }
}
