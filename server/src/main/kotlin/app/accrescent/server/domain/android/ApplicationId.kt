// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * An Android
 * [application ID](https://developer.android.com/build/configure-app-module#set-application-id).
 *
 * As per the documentation, an application ID must adhere to the following rules:
 *
 * - It must have at least two segments (one or more dots).
 * - Each segment must start with a letter.
 * - All characters must be alphanumeric or an underscore [a-zA-Z0-9_].
 *
 * In addition to Android's rules, this class also verifies that the application ID is 128
 * characters long or less to ensure an app with that ID is installable.
 *
 * @property value the string representation of this application ID.
 */
@JvmInline
value class ApplicationId private constructor(val value: String) {
    companion object {
        private val REGEX = Regex("""[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+""")

        /**
         * Parses an application ID from a string.
         *
         * See the class-level documentation for validity requirements
         *
         * @param value the value to parse an application ID from.
         * @return an application ID if [value] represents a valid application ID, or [None] if
         * [value] is not a valid application ID.
         */
        fun fromString(value: String): Option<ApplicationId> {
            return if (value.length <= 128 && REGEX.matches(value)) {
                Some(ApplicationId(value))
            } else {
                None
            }
        }
    }
}
