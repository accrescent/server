// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Response to getting an organization.
 *
 * @property organization the requested organization.
 */
data class GetOrganizationResponse(val organization: Organization)
