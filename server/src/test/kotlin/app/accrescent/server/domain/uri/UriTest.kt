// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.uri

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class UriTest {
    @Test
    fun `fromString accepts Android namespace URI`() {
        val result = Uri.fromString("http://schemas.android.com/apk/res/android")

        assertTrue(result.isSome())
    }

    @ParameterizedTest
    @MethodSource("uriRfcExamples")
    fun `fromString accepts RFC examples`(example: String) {
        val result = Uri.fromString(example)

        assertTrue(result.isSome())
    }

    @Test
    fun `instances with same representation are equal`() {
        val instance1 = Uri.fromString("https://example.com")
        val instance2 = Uri.fromString("https://example.com")

        assertEquals(instance1, instance2)
    }

    @Test
    fun `fromString and intoString round-trip data`() {
        val rawUri = "https://example.com?query=value"
        val uri = Uri.fromString(rawUri).unwrap()

        val result = uri.intoString()

        assertEquals(rawUri, result)
    }

    companion object {
        // Returns the list of URI examples given in RFC 3986. Taken directly from
        // https://datatracker.ietf.org/doc/html/rfc3986#section-1.1.2.
        @JvmStatic
        private fun uriRfcExamples(): List<String> {
            return listOf(
                "ftp://ftp.is.co.za/rfc/rfc1808.txt",
                "http://www.ietf.org/rfc/rfc2396.txt",
                "ldap://[2001:db8::7]/c=GB?objectClass?one",
                "mailto:John.Doe@example.com",
                "news:comp.infosystems.www.servers.unix",
                "tel:+1-816-555-1212",
                "telnet://192.0.2.16:80/",
                "urn:oasis:names:specification:docbook:dtd:xml:4.1.2",
            )
        }
    }
}
