// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.authn

/**
 * A response to creating a session.
 *
 * @property sessionId the ID of the created session.
 */
data class CreateSessionResponse(val sessionId: String)
