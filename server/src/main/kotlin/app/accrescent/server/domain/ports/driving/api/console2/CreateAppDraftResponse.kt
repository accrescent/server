// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Response to creating an app draft.
 *
 * @property appDraftId the ID of the created app draft.
 */
data class CreateAppDraftResponse(val appDraftId: String)
