// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console

import arrow.core.None
import arrow.core.Option

/**
 * Response to listing app draft listings.
 *
 * @property appDraftListings the app draft listings matching the request parameters in alphabetical
 * order by their language's BCP-47 codes.
 * @property nextPageToken an opaque token which, if passed to another invocation of
 * [AppDraftApi.listAppDraftListings], will return the next page of app draft listings. [None] if no
 * further pages remain.
 */
data class ListAppDraftListingsResponse(
    val appDraftListings: List<AppDraftListing>,
    val nextPageToken: Option<String>,
)
