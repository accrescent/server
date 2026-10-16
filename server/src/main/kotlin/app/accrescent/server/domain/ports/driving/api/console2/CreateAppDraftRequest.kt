// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Request to create an app draft.
 *
 * @property organizationId the ID of the organization to create the app draft in.
 */
data class CreateAppDraftRequest(val organizationId: String)
