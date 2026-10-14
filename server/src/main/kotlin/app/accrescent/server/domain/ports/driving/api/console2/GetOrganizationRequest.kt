// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Request to get an organization.
 *
 * @property organizationId the ID of the organization to get.
 */
data class GetOrganizationRequest(val organizationId: String)
