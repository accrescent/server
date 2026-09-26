// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.randomsource

import app.accrescent.server.core.NonNegativeLong
import app.accrescent.server.core.PositiveLong
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import app.accrescent.server.domain.ports.driven.randomsource.RandomSourceError
import app.accrescent.server.domain.ports.driven.randomsource.RandomSourceResult
import arrow.core.Either
import java.util.Random

private const val RNG_SEED = 0L

class DeterministicRandomSource : RandomSource() {
    private val rng = Random(RNG_SEED)

    override fun fillRandomBytes(bytes: ByteArray): RandomSourceResult<Unit> {
        return Either.Right(rng.nextBytes(bytes))
    }

    override fun randomLong(): RandomSourceResult<Long> {
        return Either.Right(rng.nextLong())
    }

    override fun randomNonNegativeLong(
        upperBound: PositiveLong,
    ): RandomSourceResult<NonNegativeLong> {
        val rawValue = rng.nextLong(upperBound.value)

        return NonNegativeLong
            .new(rawValue)
            // nextLong() returns a value in [0, upperBound), so this conversion will never fail for
            // a conforming Random
            .toEither { RandomSourceError }
    }
}
