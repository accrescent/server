// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Response to creating an app draft's listing text.
 *
 * @property etag the etag of the created listing text.
 */
data class CreateAppDraftListingTextResponse(val etag: String)
