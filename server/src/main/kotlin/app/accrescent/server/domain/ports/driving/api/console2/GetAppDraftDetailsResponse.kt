// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import arrow.core.Option

/**
 * Response to getting an app draft's details.
 *
 * @property listingText the app draft's listing text, if set.
 */
data class GetAppDraftDetailsResponse(val listingText: Option<AppDraftListingText>)
