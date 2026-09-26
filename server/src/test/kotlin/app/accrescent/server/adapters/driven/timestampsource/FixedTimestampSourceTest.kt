// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.timestampsource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class FixedTimestampSourceTest {
    private companion object {
        private val UNIX_EPOCH_TIMESTAMP = OffsetDateTime.parse("1970-01-01T00:00:00Z")
    }

    @Test
    fun `now returns Unix epoch timestamp if nothing passed to constructor`() {
        val timestamp = FixedTimestampSource().now()

        assertEquals(UNIX_EPOCH_TIMESTAMP, timestamp)
    }

    @Test
    fun `now returns timestamp passed to constructor`() {
        val timestamp = OffsetDateTime.parse("2000-01-01T00:00:00Z")
        val timestampSource = FixedTimestampSource(timestamp)

        assertEquals(timestamp, timestampSource.now())
    }
}
