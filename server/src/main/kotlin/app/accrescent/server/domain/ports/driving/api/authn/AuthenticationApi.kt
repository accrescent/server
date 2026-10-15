// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.authn

import app.accrescent.server.domain.authn.ExternalUserId
import arrow.core.Either
import arrow.core.None
import arrow.core.Option

/**
 * A user authentication API.
 */
interface AuthenticationApi {
    /**
     * Creates a session for the external user, signing them up if they do not already have an
     * account.
     *
     * This method always creates a new session even if a session for the user already exists.
     *
     * @param caller the ID of the external user to create a session for.
     */
    suspend fun createSession(
        caller: ExternalUserId,
    ): Either<CreateSessionError, CreateSessionResponse>

    /**
     * Logs out of an active session, invalidating it.
     *
     * @param sessionId the ID of the session to log out of, or [None] if none was provided.
     */
    suspend fun logOut(sessionId: Option<String>): Either<LogOutError, Unit>
}
