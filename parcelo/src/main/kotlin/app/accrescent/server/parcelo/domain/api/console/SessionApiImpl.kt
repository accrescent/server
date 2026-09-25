// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.api.console

import app.accrescent.server.parcelo.core.bindMapLeft
import app.accrescent.server.parcelo.domain.IdGenerator
import app.accrescent.server.parcelo.domain.IdType
import app.accrescent.server.parcelo.domain.authn.ExternalUserId
import app.accrescent.server.parcelo.domain.crypto.Sha256Hash
import app.accrescent.server.parcelo.domain.ports.driven.datastore.DataStore
import app.accrescent.server.parcelo.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.parcelo.domain.ports.driving.api.console.CreateSessionError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.CreateSessionResponse
import app.accrescent.server.parcelo.domain.ports.driving.api.console.SessionApi
import app.accrescent.server.parcelo.domain.ports.driving.api.console.toServerError
import arrow.core.Either
import arrow.core.None
import arrow.core.Some
import arrow.core.raise.either
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class SessionApiImpl(
    private val dataStore: DataStore,
    private val idGenerator: IdGenerator,
    private val sessionLifetime: Duration,
    private val timestampSource: TimestampSource,
) : SessionApi {
    override fun createSession(
        caller: ExternalUserId,
    ): Either<CreateSessionError, CreateSessionResponse> = either {
        val sessionId = idGenerator.generateId(IdType.SESSION).bindMapLeft(::toServerError)
        val createTime = timestampSource.now()
        val expireTime = createTime + sessionLifetime.toJavaDuration()

        dataStore.runTxWithRetry { tx ->
            val existingUserId = tx.users
                .findIdByExternalUserId(caller)
                .bindMapLeft(::toServerError)
            val userId = when (existingUserId) {
                None -> {
                    val newUserId = idGenerator.generateId(IdType.USER).bindMapLeft(::toServerError)
                    val newOrganizationId = idGenerator
                        .generateId(IdType.ORGANIZATION)
                        .bindMapLeft(::toServerError)
                    tx.organizations
                        .saveWithOwner(newOrganizationId, newUserId, caller, createTime)
                        .bindMapLeft(::toServerError)
                    newUserId
                }

                is Some -> existingUserId.value
            }
            tx.sessions
                .create(Sha256Hash.hash(sessionId.toByteArray()), userId, createTime, expireTime)
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()

        CreateSessionResponse(sessionId)
    }
}
