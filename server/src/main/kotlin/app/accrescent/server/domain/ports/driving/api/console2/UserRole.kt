// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

/**
 * A user authorization role.
 */
enum class UserRole {
    /**
     * An app reviewer.
     */
    REVIEWER,

    /**
     * An app publisher.
     */
    PUBLISHER,
}
