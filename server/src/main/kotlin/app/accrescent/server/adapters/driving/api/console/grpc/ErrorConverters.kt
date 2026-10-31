// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.core.Error
import app.accrescent.server.domain.ports.driving.api.console2.ActiveAppDraftLimitExceededError
import app.accrescent.server.domain.ports.driving.api.console2.AppDraftListingTextAlreadyExistsError
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftListingTextError
import app.accrescent.server.domain.ports.driving.api.console2.DeleteAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.GetAppDraftDetailsError
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfError
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console2.InvalidPageTokenError
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesError
import app.accrescent.server.domain.ports.driving.api.console2.ServerError
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import build.buf.gen.accrescent.console.v1.ErrorReason
import com.google.rpc.Code
import com.google.rpc.ErrorInfo
import com.google.rpc.Status
import com.google.protobuf.Any as AnyProto

private const val ERROR_DOMAIN = "console.accrescent.app"

fun toStatus(error: CreateAppDraftError): Status {
    return when (error) {
        is UnauthenticatedError -> status(ErrorReason.ERROR_REASON_UNAUTHENTICATED, error)
        is InsufficientPermissionError ->
            status(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION, error)

        is ActiveAppDraftLimitExceededError ->
            status(ErrorReason.ERROR_REASON_RESOURCE_LIMIT_EXCEEDED, error)

        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: CreateAppDraftListingTextError): Status {
    return when (error) {
        is UnauthenticatedError -> status(ErrorReason.ERROR_REASON_UNAUTHENTICATED, error)
        is InsufficientPermissionError ->
            status(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION, error)

        is AppDraftListingTextAlreadyExistsError ->
            status(ErrorReason.ERROR_REASON_ALREADY_EXISTS, error)

        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: DeleteAppDraftError): Status {
    return when (error) {
        is UnauthenticatedError -> status(ErrorReason.ERROR_REASON_UNAUTHENTICATED, error)
        is InsufficientPermissionError ->
            status(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION, error)

        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetAppDraftDetailsError): Status {
    return when (error) {
        is UnauthenticatedError -> status(ErrorReason.ERROR_REASON_UNAUTHENTICATED, error)
        is InsufficientPermissionError ->
            status(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION, error)

        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetMyOrganizationError): Status {
    return when (error) {
        is UnauthenticatedError -> status(ErrorReason.ERROR_REASON_UNAUTHENTICATED, error)
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetOrganizationError): Status {
    return when (error) {
        is UnauthenticatedError -> status(ErrorReason.ERROR_REASON_UNAUTHENTICATED, error)
        is InsufficientPermissionError ->
            status(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION, error)

        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: GetSelfError): Status {
    return when (error) {
        is UnauthenticatedError -> status(ErrorReason.ERROR_REASON_UNAUTHENTICATED, error)
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: ListAppDraftSummariesError): Status {
    return when (error) {
        is UnauthenticatedError -> status(ErrorReason.ERROR_REASON_UNAUTHENTICATED, error)
        is InsufficientPermissionError ->
            status(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION, error)

        is InvalidPageTokenError -> status(ErrorReason.ERROR_REASON_INVALID_REQUEST, error)
        is ServerError -> toStatus(error)
    }
}

fun toStatus(error: ServerError): Status {
    return when (error) {
        is ServerError.InternalServerError -> status(ErrorReason.ERROR_REASON_INTERNAL, error)
        is ServerError.ServiceUnavailableError ->
            status(ErrorReason.ERROR_REASON_SERVICE_UNAVAILABLE, error)
    }
}

/**
 * Builds a [Status] with details for console API errors.
 *
 * @param reason the reason to associate with the error.
 * @param message the developer-facing error message.
 */
fun status(reason: ErrorReason, message: String): Status {
    val code = toCode(reason)
    val errorInfo = ErrorInfo.newBuilder()
        .setReason(reason.name)
        .setDomain(ERROR_DOMAIN)
        .build()

    return Status.newBuilder()
        .setCode(code.number)
        .setMessage(message)
        .addDetails(AnyProto.pack(errorInfo))
        .build()
}

private fun status(reason: ErrorReason, error: Error): Status {
    // Intentionally don't pass error.toString() so that error causes aren't returned to clients
    return status(reason, error.message)
}

private fun toCode(reason: ErrorReason): Code {
    return when (reason) {
        ErrorReason.ERROR_REASON_RESOURCE_LIMIT_EXCEEDED -> Code.RESOURCE_EXHAUSTED
        ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION -> Code.PERMISSION_DENIED
        ErrorReason.ERROR_REASON_INVALID_REQUEST -> Code.INVALID_ARGUMENT
        ErrorReason.ERROR_REASON_UNAUTHENTICATED -> Code.UNAUTHENTICATED
        ErrorReason.ERROR_REASON_INTERNAL -> Code.INTERNAL
        ErrorReason.ERROR_REASON_SERVICE_UNAVAILABLE -> Code.UNAVAILABLE
        ErrorReason.ERROR_REASON_ALREADY_EXISTS -> Code.ALREADY_EXISTS
        ErrorReason.ERROR_REASON_ETAG_MISMATCH -> Code.ABORTED
        ErrorReason.ERROR_REASON_UNSPECIFIED, ErrorReason.UNRECOGNIZED -> Code.UNKNOWN
    }
}
