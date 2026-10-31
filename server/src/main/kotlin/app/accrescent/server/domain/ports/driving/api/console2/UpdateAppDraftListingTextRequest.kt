// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import app.accrescent.server.domain.appstore.AppName
import app.accrescent.server.domain.appstore.AppShortDescription
import arrow.core.Option

/**
 * Request to update an app draft's listing text.
 *
 * @property appDraftId the ID of the app draft to update the listing text of.
 * @property etag the etag of the listing text as last seen by the caller.
 * @property name the app's new name, if it should be updated.
 * @property shortDescription the app's new short description, if it should be updated.
 */
data class UpdateAppDraftListingTextRequest(
    val appDraftId: String,
    val etag: String,
    val name: Option<AppName>,
    val shortDescription: Option<AppShortDescription>,
)
