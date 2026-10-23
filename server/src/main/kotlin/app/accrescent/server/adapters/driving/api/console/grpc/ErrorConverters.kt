// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.core.Error
import app.accrescent.server.domain.ports.driving.api.console2.ActiveAppDraftLimitExceededError
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.DeleteAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfError
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console2.ServerError
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import com.google.rpc.Code
import com.google.rpc.Status

fun toStatus(error: CreateAppDraftError): Status {
    return when (error) {
        is UnauthenticatedError -> status(Code.UNAUTHENTICATED, error)
        is InsufficientPermissionError -> status(Code.PERMISSION_DENIED, error)
        is ActiveAppDraftLimitExceededError -> status(Code.RESOURCE_EXHAUSTED, error)
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: DeleteAppDraftError): Status {
    return when (error) {
        is UnauthenticatedError -> status(Code.UNAUTHENTICATED, error)
        is InsufficientPermissionError -> status(Code.PERMISSION_DENIED, error)
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetMyOrganizationError): Status {
    return when (error) {
        is UnauthenticatedError -> status(Code.UNAUTHENTICATED, error)
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetOrganizationError): Status {
    return when (error) {
        is UnauthenticatedError -> status(Code.UNAUTHENTICATED, error)
        is InsufficientPermissionError -> status(Code.PERMISSION_DENIED, error)
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetSelfError): Status {
    return when (error) {
        is UnauthenticatedError -> status(Code.UNAUTHENTICATED, error)
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: ServerError): Status {
    return when (error) {
        is ServerError.InternalServerError -> status(Code.INTERNAL, error)
        is ServerError.ServiceUnavailableError -> status(Code.UNAVAILABLE, error)
    }
}

private fun status(code: Code, error: Error): Status {
    return Status.newBuilder()
        .setCode(code.number)
        // Intentionally don't pass error.toString() so that error causes aren't returned to clients
        .setMessage(error.message)
        .build()
}
