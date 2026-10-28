// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import arrow.core.None
import arrow.core.Option

/**
 * Response to listing app draft summaries.
 *
 * @property appDraftSummaries the app draft summaries matching the request parameters, ordered
 * from newest to oldest.
 * @property nextPageToken an opaque token which, if passed to another invocation of
 * [ConsoleApi.listAppDraftSummaries], will return the next page of app draft summaries. [None] if
 * no further pages remain.
 */
data class ListAppDraftSummariesResponse(
    val appDraftSummaries: List<AppDraftSummary>,
    val nextPageToken: Option<String>,
)
