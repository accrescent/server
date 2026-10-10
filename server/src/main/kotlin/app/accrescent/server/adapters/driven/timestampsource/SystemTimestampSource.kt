// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.timestampsource

import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A [TimestampSource] which produces timestamps from the system clock in UTC.
 */
class SystemTimestampSource : TimestampSource {
    override fun now(): OffsetDateTime {
        return OffsetDateTime.now(ZoneOffset.UTC)
    }
}
