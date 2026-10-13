// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.ServerError
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import io.grpc.Status

fun toStatus(error: GetMyOrganizationError): Status {
    return when (error) {
        is UnauthenticatedError -> Status.UNAUTHENTICATED
        is ServerError.InternalServerError -> Status.INTERNAL
        is ServerError.ServiceUnavailableError -> Status.UNAVAILABLE
    }
}
