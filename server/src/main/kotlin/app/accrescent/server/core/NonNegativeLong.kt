// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * A 64-bit signed integer which is at least 0.
 *
 * @property value the underlying [Long] representation of this value.
 */
@JvmInline
value class NonNegativeLong private constructor(val value: Long) {
    companion object {
        fun new(value: Long): Option<NonNegativeLong> {
            return if (value >= 0) {
                Some(NonNegativeLong(value))
            } else {
                None
            }
        }
    }
}
