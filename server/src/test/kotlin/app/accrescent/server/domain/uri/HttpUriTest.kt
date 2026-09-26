// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.uri

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpUriTest {
    @Test
    fun `fromString accepts http URI`() {
        val result = HttpUri.fromString("http://example.com")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts https URI`() {
        val result = HttpUri.fromString("https://example.com")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString rejects uppercase scheme`() {
        val result = HttpUri.fromString("HTTP://example.com")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString accepts path of single slash`() {
        val result = HttpUri.fromString("http://example.com/")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts query`() {
        val result = HttpUri.fromString("http://example.com/path?key=value&key2=value2")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts empty query`() {
        val result = HttpUri.fromString("http://example.com/path?")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts port`() {
        val result = HttpUri.fromString("http://example.com:8080")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts empty port`() {
        val result = HttpUri.fromString("http://example.com:/path")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts port above the TCP port range`() {
        val result = HttpUri.fromString("http://example.com:99999/path")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts IPv4 host`() {
        val result = HttpUri.fromString("http://192.0.2.16:80/path")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts IPv6 host`() {
        val result = HttpUri.fromString("http://[2001:db8::7]/path")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString accepts percent-encoded path`() {
        val result = HttpUri.fromString("http://example.com/a%20b")

        assertTrue(result.isSome())
    }

    @Test
    fun `fromString rejects non-HTTP scheme`() {
        val result = HttpUri.fromString("ftp://example.com")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString rejects missing scheme`() {
        val result = HttpUri.fromString("//example.com/path")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString rejects missing authority`() {
        val result = HttpUri.fromString("http:example.com")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString rejects empty host`() {
        val result = HttpUri.fromString("http:///path")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString rejects userinfo`() {
        val result = HttpUri.fromString("http://user@example.com")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString rejects empty userinfo`() {
        val result = HttpUri.fromString("http://@example.com")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString rejects fragment`() {
        val result = HttpUri.fromString("http://example.com/path#section")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString rejects non-ASCII host`() {
        val result = HttpUri.fromString("http://exämple.com")

        assertTrue(result.isNone())
    }

    @Test
    fun `fromString rejects empty string`() {
        val result = HttpUri.fromString("")

        assertTrue(result.isNone())
    }

    @Test
    fun `instances with same representation are equal`() {
        val instance1 = HttpUri.fromString("https://example.com")
        val instance2 = HttpUri.fromString("https://example.com")

        assertEquals(instance1, instance2)
    }

    @Test
    fun `fromString and intoString round-trip data`() {
        val rawUri = "https://example.com/path?query=value"
        val uri = HttpUri.fromString(rawUri).unwrap()

        val result = uri.intoString()

        assertEquals(rawUri, result)
    }
}
