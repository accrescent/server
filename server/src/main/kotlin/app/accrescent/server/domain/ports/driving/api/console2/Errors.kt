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
 * An error that can occur when creating an app draft's listing text.
 */
sealed interface CreateAppDraftListingTextError

/**
 * An error that can occur when deleting an app draft.
 */
sealed interface DeleteAppDraftError

/**
 * An error that can occur when getting an app draft's details.
 */
sealed interface GetAppDraftDetailsError

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
 * An error that can occur when updating an app draft's listing text.
 */
sealed interface UpdateAppDraftListingTextError

/**
 * A server-side error that can occur in any console API method.
 */
sealed class ServerError :
    Error(),
    CreateAppDraftError,
    CreateAppDraftListingTextError,
    DeleteAppDraftError,
    GetAppDraftDetailsError,
    GetMyOrganizationError,
    GetOrganizationError,
    GetSelfError,
    ListAppDraftSummariesError,
    UpdateAppDraftListingTextError {
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
 * The app draft already has listing text.
 */
class AppDraftListingTextAlreadyExistsError : Error(), CreateAppDraftListingTextError {
    override val message = "app draft listing text already exists"
}

/**
 * The provided etag does not match the resource's current etag.
 */
class EtagMismatchError : Error(), UpdateAppDraftListingTextError {
    override val message = "etag mismatch"
}

/**
 * The caller does not have sufficient permission to perform the operation.
 */
class InsufficientPermissionError :
    Error(),
    CreateAppDraftError,
    CreateAppDraftListingTextError,
    DeleteAppDraftError,
    GetAppDraftDetailsError,
    GetOrganizationError,
    ListAppDraftSummariesError,
    UpdateAppDraftListingTextError {
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
    CreateAppDraftListingTextError,
    DeleteAppDraftError,
    GetAppDraftDetailsError,
    GetMyOrganizationError,
    GetOrganizationError,
    GetSelfError,
    ListAppDraftSummariesError,
    UpdateAppDraftListingTextError {
    override val message = "unauthenticated"
}
