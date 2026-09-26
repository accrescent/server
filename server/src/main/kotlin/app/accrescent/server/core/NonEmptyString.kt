// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * A character string containing at least one character.
 *
 * @property value the string representation of this non-empty string.
 */
@JvmInline
value class NonEmptyString private constructor(val value: String) {
    companion object {
        /**
         * Creates a non-empty string from a string.
         *
         * @param value the string to create a non-empty string from.
         * @return a non-empty string with the value of [value], or [None] if [value] is empty.
         */
        fun fromString(value: String): Option<NonEmptyString> {
            return if (value.isNotEmpty()) {
                Some(NonEmptyString(value))
            } else {
                None
            }
        }
    }
}
