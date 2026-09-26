// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driving.api.console.ServerError
import app.accrescent.server.domain.ports.driving.api.console.UnauthenticatedError
import app.accrescent.server.domain.ports.driving.api.console.toServerError
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.raise.Raise
import java.time.OffsetDateTime

/**
 * Authenticates the user authenticated by the provided session ID.
 *
 * @param tx the transaction session lookup should participate in.
 * @param sessionId the ID of the session to authenticate with, or [None] if none was provided.
 * @param currentTime the current timestamp to be used for checking session expiration.
 * @return the ID of the user the session authenticated, or [UnauthenticatedError] if no session ID
 * was provided, the session does not exist, or the session is expired.
 */
context(_: Raise<ServerError>)
fun authenticateCaller(
    tx: DataStore.Transaction,
    sessionId: Option<String>,
    currentTime: OffsetDateTime,
): Either<UnauthenticatedError, String> {
    return when (sessionId) {
        None -> Either.Left(UnauthenticatedError)

        is Some -> tx.users
            .findIdBySessionIdHash(Sha256Hash.hash(sessionId.value.toByteArray()), currentTime)
            .bindMapLeft(::toServerError)
            .toEither { UnauthenticatedError }
    }
}
