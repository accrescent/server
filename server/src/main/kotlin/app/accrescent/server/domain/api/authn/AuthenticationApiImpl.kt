// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.authn

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.causedBy
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.IdType
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.config.SessionLifetime
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.authn.AuthenticationApi
import app.accrescent.server.domain.ports.driving.api.authn.CreateSessionError
import app.accrescent.server.domain.ports.driving.api.authn.CreateSessionResponse
import app.accrescent.server.domain.ports.driving.api.authn.LogOutError
import app.accrescent.server.domain.ports.driving.api.authn.ServerError
import app.accrescent.server.domain.ports.driving.api.authn.UnauthenticatedError
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlin.time.toJavaDuration

/**
 * Primary implementation of [AuthenticationApi].
 *
 * @param dataStore the data store to use for data persistence.
 * @param idGenerator the generator of session, user, and organization IDs.
 * @param sessionLifetime how long created sessions remain valid.
 * @param timestampSource the source of timestamps to use.
 */
class AuthenticationApiImpl(
    private val dataStore: DataStore,
    private val idGenerator: IdGenerator,
    private val sessionLifetime: SessionLifetime,
    private val timestampSource: TimestampSource,
) : AuthenticationApi {
    override suspend fun createSession(
        caller: ExternalUserId,
    ): Either<CreateSessionError, CreateSessionResponse> = either {
        val sessionId = generateId(IdType.SESSION)
        val idHash = Sha256Hash.hash(sessionId.toByteArray())
        val createTime = timestampSource.now()
        val expireTime = createTime + sessionLifetime.value.toJavaDuration()

        dataStore.runInTransaction { tx ->
            val existingUserId = tx
                .findUserIdByExternalUserId(caller)
                .bindMapLeft(::toServerError)
            val userId = when (existingUserId) {
                None -> {
                    val newUserId = generateId(IdType.USER)
                    val newOrganizationId = generateId(IdType.ORGANIZATION)
                    tx.createOrgWithOwner(newOrganizationId, newUserId, caller, createTime)
                        .bindMapLeft(::toServerError)
                    newUserId
                }

                is Some -> existingUserId.value
            }
            tx.createSession(idHash, userId, createTime, expireTime).bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()

        CreateSessionResponse(sessionId)
    }

    override suspend fun logOut(sessionId: Option<String>): Either<LogOutError, Unit> = either {
        val idHash = sessionId
            .toEitherBind(::UnauthenticatedError)
            .let { Sha256Hash.hash(it.toByteArray()) }
        val currentTime = timestampSource.now()

        dataStore.runInTransaction { tx ->
            val deleted = tx.deleteActiveSession(idHash, currentTime).bindMapLeft(::toServerError)
            ensure(deleted, ::UnauthenticatedError)
        }
            .bindMapLeft(::toServerError)
            .bind()
    }

    context(raise: Raise<CreateSessionError>)
    private fun generateId(type: IdType): String {
        return idGenerator
            .generateId(type)
            .bindMapLeft { ServerError.InternalServerError().causedBy(it) }
    }
}
