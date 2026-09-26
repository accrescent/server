// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console

import app.accrescent.server.domain.appstore.ListingLanguage

data class CreateAppDraftListingRequest(
    val appDraftId: String,
    val language: ListingLanguage,
    val name: String,
    val shortDescription: String,
)
