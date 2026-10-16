// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console2

import app.accrescent.server.core.causedBy
import app.accrescent.server.domain.IdGenerationError
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreError
import app.accrescent.server.domain.ports.driving.api.console2.ServerError

/**
 * Converts a [DataStoreError] into its corresponding [ServerError].
 *
 * @param error the data store error to convert.
 * @return the corresponding server error, caused by [error].
 */
fun toServerError(error: DataStoreError): ServerError {
    val serverError = when (error) {
        is DataStoreError.ConsistencyViolation,
        is DataStoreError.EntityNotFound,
        is DataStoreError.IllegalState,
        is DataStoreError.Unknown -> ServerError.InternalServerError()

        is DataStoreError.SerializationFailure -> ServerError.ServiceUnavailableError()
    }

    return serverError.causedBy(error)
}

/**
 * Converts an [IdGenerationError] into its corresponding [ServerError].
 *
 * @param error the ID generation error to convert.
 * @return the corresponding server error, caused by [error].
 */
fun toServerError(error: IdGenerationError): ServerError {
    return ServerError.InternalServerError().causedBy(error)
}
