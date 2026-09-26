// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.blobstorage

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UriLifetimeTest {
    @Test
    fun `rejects construction with duration of less than 1 second`() {
        assertTrue(UriLifetime.new(0u).isNone())
    }

    @Test
    fun `accepts construction with duration of 1 second`() {
        assertTrue(UriLifetime.new(1u).isSome())
    }

    // According to https://docs.cloud.google.com/storage/docs/authentication/creating-signatures,
    // signed URLs created with Google Cloud's signBlob method are guaranteed to be valid for up to
    // 12 hours at most, with URLs expiring later than that possibly failing to work any time after
    // 12 hours have passed. Since we want to use signBlob with Google Cloud Storage, this behavior
    // places a 12-hour upper bound on the practical lifetime of our signed URLs.
    @Test
    fun `rejects construction with duration of more than 12 hours`() {
        assertTrue(UriLifetime.new(43201u).isNone())
    }

    @Test
    fun `accepts construction with duration of 12 hours`() {
        assertTrue(UriLifetime.new(43200u).isSome())
    }
}