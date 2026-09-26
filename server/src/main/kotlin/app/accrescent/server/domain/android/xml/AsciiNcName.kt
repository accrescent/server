// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * An XML [NCName](https://www.w3.org/TR/2009/REC-xml-names-20091208/#NT-NCName) restricted to ASCII
 * characters.
 *
 * @property value the string representation of this ASCII NCName.
 */
@JvmInline
value class AsciiNcName private constructor(val value: String) {
    companion object {
        private val REGEX = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")

        /**
         * Parses an ASCII NCName from a string.
         *
         * @param value the value to parse an ASCII NCName from.
         * @return an ASCII NCName if [value] represents a valid ASCII NCName, or [None] if [value]
         * is not a valid ASCII NCName.
         */
        fun fromString(value: String): Option<AsciiNcName> {
            return if (REGEX.matches(value)) {
                Some(AsciiNcName(value))
            } else {
                None
            }
        }
    }
}
