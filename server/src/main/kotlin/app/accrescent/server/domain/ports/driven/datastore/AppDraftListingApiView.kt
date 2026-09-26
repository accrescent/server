// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

import app.accrescent.server.domain.appstore.ListingLanguage

/**
 * The API view of an app store listing for an app in draft stage.
 *
 * @property id the unique ID of this app draft listing.
 * @property appDraftId the ID of the app draft this listing belongs to.
 * @property language the language this listing's contents are written in.
 * @property name the name of the app, limited to 30 characters.
 * @property shortDescription a short description of the app, limited to 80 characters.
 */
data class AppDraftListingApiView(
    val id: String,
    val appDraftId: String,
    val language: ListingLanguage,
    val name: String,
    val shortDescription: String,
)
