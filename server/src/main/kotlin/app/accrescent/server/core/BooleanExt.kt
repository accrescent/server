// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(ExperimentalContracts::class)

package app.accrescent.server.core

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

inline fun <E> Boolean.okOrElse(error: () -> E): Either<E, Unit> {
    contract { callsInPlace(error, InvocationKind.AT_MOST_ONCE) }

    return if (this) {
        Either.Right(Unit)
    } else {
        Either.Left(error())
    }
}

inline fun <T> Boolean.then(f: () -> T): Option<T> {
    contract { callsInPlace(f, InvocationKind.AT_MOST_ONCE) }

    return if (this) {
        Some(f())
    } else {
        None
    }
}
