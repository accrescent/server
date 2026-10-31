// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.appstore

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * A short description of an app as shown in its listing in headers and small screen spaces.
 *
 * A short description is between 1 and 80 Unicode code points long inclusive.
 *
 * @property value the string representation of this short description.
 */
@JvmInline
value class AppShortDescription private constructor(val value: String) {
    companion object {
        private const val MIN_LENGTH = 1
        private const val MAX_LENGTH = 80

        /**
         * Creates a short description from a string.
         *
         * @param value the value to create a short description from.
         * @return a short description with the value of [value], or [None] if [value] is not a
         * valid short description.
         */
        fun fromString(value: String): Option<AppShortDescription> {
            return if (value.codePointCount(0, value.length) in MIN_LENGTH..MAX_LENGTH) {
                Some(AppShortDescription(value))
            } else {
                None
            }
        }
    }
}
