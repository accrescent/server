// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * A 32-bit signed integer which is at least 0.
 *
 * @property value the underlying [Int] representation of this value.
 */
@JvmInline
value class NonNegativeInt private constructor(val value: Int) {
    companion object {
        fun new(value: Int): Option<NonNegativeInt> {
            return if (value >= 0) {
                Some(NonNegativeInt(value))
            } else {
                None
            }
        }
    }
}
