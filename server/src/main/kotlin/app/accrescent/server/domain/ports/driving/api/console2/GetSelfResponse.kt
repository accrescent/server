// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * Response to getting the authenticated user.
 *
 * @property user the authenticated user.
 */
data class GetSelfResponse(val user: User)
