// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Response to getting the authenticated user's organization.
 *
 * @property organizationId the authenticated user's organization ID.
 */
data class GetMyOrganizationResponse(val organizationId: String)
