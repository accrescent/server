// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.Either

data object IntegerOutOfRangeError

fun Long.intoULong(): Either<IntegerOutOfRangeError, ULong> {
    return if (this >= 0) {
        Either.Right(this.toULong())
    } else {
        Either.Left(IntegerOutOfRangeError)
    }
}

fun ULong.intoInt(): Either<IntegerOutOfRangeError, Int> {
    return if (this <= Int.MAX_VALUE.toULong()) {
        // We've guaranteed this conversion is lossless
        @Suppress("TruncatingIntegerConversion")
        Either.Right(this.toInt())
    } else {
        Either.Left(IntegerOutOfRangeError)
    }
}
