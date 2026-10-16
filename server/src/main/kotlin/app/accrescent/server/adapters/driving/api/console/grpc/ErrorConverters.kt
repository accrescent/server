// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.ActiveAppDraftLimitExceededError
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfError
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console2.ServerError
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import io.grpc.Status

fun toStatus(error: CreateAppDraftError): Status {
    return when (error) {
        is UnauthenticatedError -> Status.UNAUTHENTICATED
        is InsufficientPermissionError -> Status.PERMISSION_DENIED
        is ActiveAppDraftLimitExceededError -> Status.RESOURCE_EXHAUSTED
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetMyOrganizationError): Status {
    return when (error) {
        is UnauthenticatedError -> Status.UNAUTHENTICATED
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetOrganizationError): Status {
    return when (error) {
        is UnauthenticatedError -> Status.UNAUTHENTICATED
        is InsufficientPermissionError -> Status.PERMISSION_DENIED
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetSelfError): Status {
    return when (error) {
        is UnauthenticatedError -> Status.UNAUTHENTICATED
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: ServerError): Status {
    return when (error) {
        is ServerError.InternalServerError -> Status.INTERNAL
        is ServerError.ServiceUnavailableError -> Status.UNAVAILABLE
    }
}
