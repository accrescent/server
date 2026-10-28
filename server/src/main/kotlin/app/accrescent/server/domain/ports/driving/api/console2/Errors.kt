// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import app.accrescent.server.core.Error

/**
 * An error that can occur when creating an app draft.
 */
sealed interface CreateAppDraftError

/**
 * An error that can occur when deleting an app draft.
 */
sealed interface DeleteAppDraftError

/**
 * An error that can occur when getting the authenticated user's organization.
 */
sealed interface GetMyOrganizationError

/**
 * An error that can occur when getting an organization.
 */
sealed interface GetOrganizationError

/**
 * An error that can occur when getting the authenticated user.
 */
sealed interface GetSelfError

/**
 * An error that can occur when listing app draft summaries.
 */
sealed interface ListAppDraftSummariesError

/**
 * A server-side error that can occur in any console API method.
 */
sealed class ServerError :
    Error(),
    CreateAppDraftError,
    DeleteAppDraftError,
    GetMyOrganizationError,
    GetOrganizationError,
    GetSelfError,
    ListAppDraftSummariesError {
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
 * The organization already has the maximum number of active app drafts allowed.
 */
class ActiveAppDraftLimitExceededError : Error(), CreateAppDraftError {
    override val message = "active app draft limit exceeded"
}

/**
 * The caller does not have sufficient permission to perform the operation.
 */
class InsufficientPermissionError :
    Error(),
    CreateAppDraftError,
    DeleteAppDraftError,
    GetOrganizationError,
    ListAppDraftSummariesError {
    override val message = "insufficient permission"
}

/**
 * The provided page token is invalid.
 */
class InvalidPageTokenError : Error(), ListAppDraftSummariesError {
    override val message = "invalid page token"
}

/**
 * The caller of a method which requires authentication is not authenticated.
 */
class UnauthenticatedError :
    Error(),
    CreateAppDraftError,
    DeleteAppDraftError,
    GetMyOrganizationError,
    GetOrganizationError,
    GetSelfError,
    ListAppDraftSummariesError {
    override val message = "unauthenticated"
}
