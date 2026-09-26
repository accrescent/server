// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * An Android manifest
 * [name attribute](https://developer.android.com/reference/android/R.attr#name) value.
 *
 * A name attribute is
 * [limited to 1024 characters](https://developer.android.com/guide/topics/manifest/manifest-intro#limits),
 * which this class interprets to mean Unicode code points in accordance with
 * [AIP 210](https://google.aip.dev/210#character-definition).
 *
 * @property value the string representation of this name attribute.
 */
@JvmInline
value class NameAttribute private constructor(val value: String) {
    companion object {
        private const val MAX_LENGTH = 1024

        /**
         * Creates a name attribute from a string.
         *
         * @param value the value to create a name attribute from.
         * @return a name attribute with the value of [value], or [None] if [value] is not a valid
         * name attribute.
         */
        fun fromString(value: String): Option<NameAttribute> {
            return if (value.codePointCount(0, value.length) <= MAX_LENGTH) {
                Some(NameAttribute(value))
            } else {
                None
            }
        }
    }
}
