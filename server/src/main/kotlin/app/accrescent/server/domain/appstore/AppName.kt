// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.appstore

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * The proper name of an app as shown in its listing, possibly including very short descriptive text
 * (e.g., "SecureChat - Secure Texting").
 *
 * An app name is between 1 and 30 Unicode code points long inclusive.
 *
 * @property value the string representation of this app name.
 */
@JvmInline
value class AppName private constructor(val value: String) {
    companion object {
        private const val MIN_LENGTH = 1
        private const val MAX_LENGTH = 30

        /**
         * Creates an app name from a string.
         *
         * @param value the value to create an app name from.
         * @return an app name with the value of [value], or [None] if [value] is not a valid app
         * name.
         */
        fun fromString(value: String): Option<AppName> {
            return if (value.codePointCount(0, value.length) in MIN_LENGTH..MAX_LENGTH) {
                Some(AppName(value))
            } else {
                None
            }
        }
    }
}
