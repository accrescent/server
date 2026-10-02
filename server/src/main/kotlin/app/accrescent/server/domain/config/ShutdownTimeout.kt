// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.config

import arrow.core.None
import arrow.core.Option
import arrow.core.toOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A server shutdown timeout consisting of an integer number of seconds in the range [0, 60].
 *
 * @property value the underlying [Duration] of this timeout.
 */
@JvmInline
value class ShutdownTimeout private constructor(val value: Duration) {
    companion object {
        private val REGEX = Regex("([0-9]|[1-5][0-9]|60)s")

        /**
         * Parses a shutdown timeout from a string.
         *
         * The string must contain only an integer number of seconds in the range [0, 60] followed
         * by an "s", e.g., "30s".
         *
         * @param value the string to parse.
         * @return the parsed shutdown timeout, or [None] if [value] is not a valid shutdown timeout.
         */
        fun parse(value: String): Option<ShutdownTimeout> {
            return REGEX.matchEntire(value)
                .toOption()
                .map { ShutdownTimeout(it.groupValues[1].toInt().seconds) }
        }
    }
}
