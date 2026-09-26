// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.randomsource

import app.accrescent.server.core.NonNegativeLong
import app.accrescent.server.core.PositiveLong
import arrow.core.Either

typealias RandomSourceResult<T> = Either<RandomSourceError, T>

data object RandomSourceError

abstract class RandomSource {
    abstract fun fillRandomBytes(bytes: ByteArray): RandomSourceResult<Unit>
    abstract fun randomLong(): RandomSourceResult<Long>

    /**
     * Generates a uniformly distributed random value in `[0, upperBound)`.
     *
     * @param upperBound the exclusive upper bound of the generated value.
     * @return the generated value, or [RandomSourceError] if the underlying source failed.
     */
    abstract fun randomNonNegativeLong(upperBound: PositiveLong): RandomSourceResult<NonNegativeLong>
}
