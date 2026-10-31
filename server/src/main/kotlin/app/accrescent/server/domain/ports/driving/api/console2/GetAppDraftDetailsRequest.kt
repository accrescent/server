// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Request to get an app draft's details.
 *
 * @property appDraftId the ID of the app draft to get the details of.
 */
data class GetAppDraftDetailsRequest(val appDraftId: String)
