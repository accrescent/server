// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.config

import arrow.core.None
import arrow.core.Option
import arrow.core.toOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * A user session lifetime consisting of an integer number of hours in the range [1, 8760].
 *
 * @property value the underlying [Duration] of this lifetime.
 */
@JvmInline
value class SessionLifetime private constructor(val value: Duration) {
    companion object {
        private const val MAX_HOURS = 8760
        private val REGEX = Regex("([1-9][0-9]{0,3})h")

        /**
         * Parses a session lifetime from a string.
         *
         * The string must contain only an integer number of hours in the range [1, 8760] followed
         * by an "h", e.g., "24h".
         *
         * @param value the string to parse.
         * @return the parsed session lifetime, or [None] if [value] is not a valid session
         * lifetime.
         */
        fun parse(value: String): Option<SessionLifetime> {
            return REGEX.matchEntire(value)
                .toOption()
                .map { it.groupValues[1].toInt() }
                .filter { it <= MAX_HOURS }
                .map { SessionLifetime(it.hours) }
        }
    }
}
