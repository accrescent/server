// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import app.accrescent.server.core.Error

/**
 * An error that can occur when getting the authenticated user's organization.
 */
sealed interface GetMyOrganizationError

/**
 * A server-side error that can occur in any console API method.
 */
sealed class ServerError : Error(), GetMyOrganizationError {
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
class UnauthenticatedError : Error(), GetMyOrganizationError {
    override val message = "unauthenticated"
}
