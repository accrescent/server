// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.ports.driving.api.console

import app.accrescent.server.parcelo.domain.authn.ExternalUserId
import arrow.core.Either

interface SessionApi {
    /**
     * Creates a session for the external user, signing them up if they do not already have an
     * account.
     *
     * This method always creates a new session even if a session for the user already exists.
     *
     * @param caller the ID of the external user to create a session for.
     */
    fun createSession(caller: ExternalUserId): Either<CreateSessionError, CreateSessionResponse>
}
