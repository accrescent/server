// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import app.accrescent.server.core.PositiveInt
import arrow.core.None
import arrow.core.Option

/**
 * Request to list app draft summaries.
 *
 * @property organizationId the ID of the organization containing the app drafts to list.
 * @property pageSize the maximum number of app draft summaries to return in the response. If
 * [None], defaults to 50. All requests with a higher page size are capped to 50.
 * @property pageToken an opaque page continuation token returned in a previous
 * [ListAppDraftSummariesResponse]. If [None], the first page is returned.
 */
data class ListAppDraftSummariesRequest(
    val organizationId: String,
    val pageSize: Option<PositiveInt>,
    val pageToken: Option<String>,
)
