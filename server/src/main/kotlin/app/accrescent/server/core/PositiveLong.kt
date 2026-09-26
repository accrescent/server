// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

@JvmInline
value class PositiveLong private constructor(val value: Long) {
    companion object {
        fun new(value: Long): Option<PositiveLong> {
            return if (value > 0) {
                Some(PositiveLong(value))
            } else {
                None
            }
        }
    }

    override fun toString() = value.toString()
}
