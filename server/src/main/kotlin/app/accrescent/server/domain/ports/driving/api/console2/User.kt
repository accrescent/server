// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * A console user.
 *
 * @property id this user's unique ID.
 * @property roles this user's authorization roles.
 */
data class User(val id: String, val roles: Set<UserRole>)
