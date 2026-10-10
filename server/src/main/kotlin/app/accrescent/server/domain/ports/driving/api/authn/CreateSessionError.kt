// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.authn

import app.accrescent.server.core.Error

/**
 * An error that can occur when attempting to create a session.
 */
sealed class CreateSessionError : Error() {
    /**
     * The server encountered an unexpected internal error.
     */
    class InternalServerError : CreateSessionError() {
        override val message = "internal server error"
    }

    /**
     * The server is temporarily unable to create the session.
     */
    class ServiceUnavailableError : CreateSessionError() {
        override val message = "service unavailable"
    }
}
