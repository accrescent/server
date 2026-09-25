// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.ports.driving.api.console

import arrow.core.None
import arrow.core.Option

/**
 * A request for listing app draft listings.
 *
 * @property appDraftId the ID of the app draft to list listings for.
 * @property pageSize the maximum number of app draft listings to return in the response. If
 * unspecified, defaults to 50. All requests with a higher page size will be capped to 50.
 * @property nextPageToken an opaque page continuation token returned in a previous
 * [ListAppDraftListingsResponse]. If [None], the first page is returned.
 */
data class ListAppDraftListingsRequest(
    val appDraftId: String,
    val pageSize: UInt,
    val nextPageToken: Option<String>,
)
