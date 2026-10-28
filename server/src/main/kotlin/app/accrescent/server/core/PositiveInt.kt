// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * A 32-bit signed integer which is at least 1.
 *
 * @property value the underlying [Int] representation of this value.
 */
@JvmInline
value class PositiveInt private constructor(val value: Int) : Comparable<PositiveInt> {
    companion object {
        fun new(value: Int): Option<PositiveInt> {
            return if (value > 0) {
                Some(PositiveInt(value))
            } else {
                None
            }
        }
    }

    override fun compareTo(other: PositiveInt): Int {
        return value.compareTo(other.value)
    }
}
