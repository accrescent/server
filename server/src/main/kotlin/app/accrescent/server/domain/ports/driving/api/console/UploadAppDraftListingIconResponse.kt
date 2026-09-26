// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console

import app.accrescent.server.domain.uri.HttpUri

/**
 * A response to requesting an upload for an app draft listing's icon.
 *
 * @property uploadUri an HTTP(S) URI at which the icon can be submitted with an HTTP PUT request.
 */
data class UploadAppDraftListingIconResponse(val uploadUri: HttpUri)
