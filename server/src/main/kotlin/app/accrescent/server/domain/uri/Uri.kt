// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.uri

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import org.apache.jena.rfc3986.RFC3986

/**
 * A Uniform Resource Identifier (URI) as defined by
 * [RFC 3986](https://datatracker.ietf.org/doc/html/rfc3986).
 */
@JvmInline
value class Uri private constructor(private val value: String) {
    companion object {
        /**
         * Parses a URI from a string according to RFC 3986 syntax.
         *
         * @return a URI if the string is a valid URI, or [None] otherwise.
         */
        fun fromString(value: String): Option<Uri> {
            return try {
                when (RFC3986.create(value).isRFC3986) {
                    true -> Some(Uri(value))
                    false -> None
                }
            } catch (_: RuntimeException) {
                None
            }
        }
    }

    /**
     * Converts this URI into its string representation.
     *
     * @return the string representation of this URI.
     */
    fun intoString(): String {
        return value
    }
}
