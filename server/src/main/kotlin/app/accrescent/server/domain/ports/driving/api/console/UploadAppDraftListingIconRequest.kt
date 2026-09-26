// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console

/**
 * A request for uploading an app draft listing's icon.
 *
 * @property appDraftListingId the ID of the app draft listing to upload an icon for.
 */
data class UploadAppDraftListingIconRequest(val appDraftListingId: String)
