// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.timestampsource

import java.time.OffsetDateTime

interface TimestampSource {
    /**
     * Gets the current timestamp.
     *
     * This method is thread-safe.
     *
     * @return the current timestamp.
     */
    fun now(): OffsetDateTime
}
