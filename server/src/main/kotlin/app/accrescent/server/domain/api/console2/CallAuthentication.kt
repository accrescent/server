// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console2

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driving.api.console2.ServerError
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import arrow.core.None
import arrow.core.Option
import arrow.core.raise.Raise
import java.time.OffsetDateTime

/**
 * Authenticates the user authenticated by the provided session ID.
 *
 * Raises [UnauthenticatedError] if no session ID was provided, the session does not exist, or the
 * session is expired; or [ServerError] if looking up the session fails.
 *
 * @param tx the transaction session lookup should participate in.
 * @param sessionId the ID of the session to authenticate with, or [None] if none was provided.
 * @param currentTime the current timestamp to be used for checking session expiration.
 * @return the ID of the user the session authenticated.
 */
// Explicit type arguments are required to disambiguate the Raise context arguments
@Suppress("RemoveExplicitTypeArguments")
context(_: Raise<UnauthenticatedError>, _: Raise<ServerError>)
suspend fun authenticateCaller(
    tx: DataStore.Transaction,
    sessionId: Option<String>,
    currentTime: OffsetDateTime,
): String {
    val idHash = sessionId
        .toEitherBind<_, UnauthenticatedError>(::UnauthenticatedError)
        .let { Sha256Hash.hash(it.toByteArray()) }

    return tx.findUserIdByActiveSessionIdHash(idHash, currentTime)
        .bindMapLeft<_, ServerError, _>(::toServerError)
        .toEitherBind<_, UnauthenticatedError>(::UnauthenticatedError)
}
