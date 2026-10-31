// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Response to updating an app draft's listing text.
 *
 * @property etag the etag of the listing text after the update.
 */
data class UpdateAppDraftListingTextResponse(val etag: String)
