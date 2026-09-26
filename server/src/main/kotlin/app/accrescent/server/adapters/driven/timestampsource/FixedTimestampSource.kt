// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.timestampsource

import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import java.time.OffsetDateTime

/**
 * A [TimestampSource] which produces a fixed timestamp value.
 *
 * [FixedTimestampSource]'s [now] method will always return the timestamp it was initialized with.
 * The default timestamp is the Unix epoch.
 */
class FixedTimestampSource(
    private val timestamp: OffsetDateTime = UNIX_EPOCH_TIMESTAMP,
) : TimestampSource {
    private companion object {
        private val UNIX_EPOCH_TIMESTAMP = OffsetDateTime.parse("1970-01-01T00:00:00Z")
    }

    override fun now(): OffsetDateTime {
        return timestamp
    }
}
