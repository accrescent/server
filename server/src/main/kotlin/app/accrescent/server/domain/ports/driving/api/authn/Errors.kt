// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.authn

import app.accrescent.server.core.Error

/**
 * An error that can occur when attempting to create a session.
 */
sealed interface CreateSessionError

/**
 * An error that can occur when attempting to log out.
 */
sealed interface LogOutError

/**
 * A server-side error that can occur in any authentication API method.
 */
sealed class ServerError : Error(), CreateSessionError, LogOutError {
    /**
     * The server encountered an unexpected internal error.
     */
    class InternalServerError : ServerError() {
        override val message = "internal server error"
    }

    /**
     * The server is temporarily unable to complete the operation.
     */
    class ServiceUnavailableError : ServerError() {
        override val message = "service unavailable"
    }
}

/**
 * The caller of a method which requires authentication is not authenticated.
 */
class UnauthenticatedError : Error(), LogOutError {
    override val message = "unauthenticated"
}
