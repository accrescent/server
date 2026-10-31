// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * The text fields of an app draft's listing.
 *
 * @property name the app's name.
 * @property shortDescription the app's short description.
 * @property etag the etag of the listing text.
 */
data class AppDraftListingText(
    val name: String,
    val shortDescription: String,
    val etag: String,
)
