// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Request to delete an app draft.
 *
 * @property appDraftId the ID of the app draft to delete.
 */
data class DeleteAppDraftRequest(val appDraftId: String)
