// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

import arrow.core.Option
import java.time.OffsetDateTime

/**
 * The view of an app draft summary exposed through the API.
 *
 * @property id the app draft's unique ID.
 * @property name the app's name from the draft's listing text, if set.
 * @property createTime the app draft's creation timestamp.
 */
data class AppDraftSummaryApiView(
    val id: String,
    val name: Option<String>,
    val createTime: OffsetDateTime,
)
