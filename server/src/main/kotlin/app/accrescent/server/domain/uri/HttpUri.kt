// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.uri

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import org.apache.jena.rfc3986.RFC3986

/**
 * An HTTP or HTTPS URI as defined by [RFC 9110](https://datatracker.ietf.org/doc/html/rfc9110)
 * [Section 4.2.1](https://datatracker.ietf.org/doc/html/rfc9110#name-http-uri-scheme) and
 * [Section 4.2.2](https://datatracker.ietf.org/doc/html/rfc9110#name-https-uri-scheme).
 */
@JvmInline
value class HttpUri private constructor(private val value: String) {
    companion object {
        /**
         * Parses an HTTP(S) URI from a string according to RFC 9110 syntax.
         *
         * @param value the value to parse an HTTP(S) URI from.
         * @return an HTTP(S) URI if the string is a valid HTTP(S) URI, or [None] otherwise.
         */
        fun fromString(value: String): Option<HttpUri> {
            val iri = try {
                RFC3986.create(value)
            } catch (_: RuntimeException) {
                return None
            }

            // RFC 9110 HTTP(S) URIs are a strict subset of RFC 3986 URIs
            if (!iri.isRFC3986) return None

            // RFC 9110 HTTP(S) URIs always start with "http://" or "https://" according to
            // https://datatracker.ietf.org/doc/html/rfc9110#name-http-uri-scheme and
            // https://datatracker.ietf.org/doc/html/rfc9110#name-https-uri-scheme
            if (!(value.startsWith("http://") || value.startsWith("https://"))) return None

            // Section 4.2.4 says userinfo for http(s) URIs is deprecated and implementations
            // should treat its presence as an error, so we do so here
            if (iri.hasUserInfo()) return None

            // RFC 9110 states that empty host identifiers must be rejected as invalid
            if (iri.host().isNullOrEmpty()) return None

            // path must be path-abempty, so it must either start with "/" or be empty
            if (!(iri.path().isNullOrEmpty() || iri.path().startsWith('/'))) return None

            // RFC 9110 does not allow a fragment component, unlike RFC 7230 and RFC 3986
            if (iri.hasFragment()) return None

            return Some(HttpUri(value))
        }
    }

    /**
     * Converts this HTTP(S) URI into its string representation.
     *
     * @return the string representation of this URI.
     */
    fun intoString(): String {
        return value
    }
}
