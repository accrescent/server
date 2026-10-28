// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

import java.time.OffsetDateTime

/**
 * The view of an app draft summary exposed through the API.
 *
 * @property id the app draft's unique ID.
 * @property createTime the app draft's creation timestamp.
 */
data class AppDraftSummaryApiView(val id: String, val createTime: OffsetDateTime)
