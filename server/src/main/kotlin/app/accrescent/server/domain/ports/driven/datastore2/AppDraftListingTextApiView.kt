// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

/**
 * The view of an app draft's listing text exposed through the API.
 *
 * @property name the app's name.
 * @property shortDescription the app's short description.
 * @property etag a content-based ETag of the listing text.
 */
data class AppDraftListingTextApiView(
    val name: String,
    val shortDescription: String,
    val etag: String,
)
