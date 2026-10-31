// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import app.accrescent.server.domain.appstore.AppName
import app.accrescent.server.domain.appstore.AppShortDescription

/**
 * Request to create an app draft's listing text.
 *
 * @property appDraftId the ID of the app draft to create the listing text for.
 * @property name the app's name.
 * @property shortDescription the app's short description.
 */
data class CreateAppDraftListingTextRequest(
    val appDraftId: String,
    val name: AppName,
    val shortDescription: AppShortDescription,
)
