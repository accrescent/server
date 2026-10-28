// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

import java.time.OffsetDateTime

/**
 * The key app draft summaries are ordered by when listed.
 *
 * App draft summaries are ordered from newest to oldest. Specifically, they are ordered by
 * descending creation time with ties broken by descending app draft ID.
 *
 * @property createTime the app draft's creation timestamp.
 * @property id the app draft's unique ID.
 */
data class AppDraftSummarySortKey(val createTime: OffsetDateTime, val id: String)
