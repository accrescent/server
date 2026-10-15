// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.authn

import app.accrescent.server.adapters.driven.datastore2.vertx.MemoryDataStore
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrapErr
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.config.SessionLifetime
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.authn.AuthenticationApi
import app.accrescent.server.domain.ports.driving.api.authn.UnauthenticatedError
import arrow.core.None
import arrow.core.Some
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class AuthenticationApiImplTest {
    @Test
    suspend fun `calling createSession twice for same external user returns two different sessions`() {
        withMigratedDataStore { dataStore ->
            val api = makeAuthenticationApi(dataStore)
            val caller = ExternalUserId.Github(1)

            val firstResponse = api.createSession(caller).unwrap()
            val secondResponse = api.createSession(caller).unwrap()

            assertNotEquals(firstResponse.sessionId, secondResponse.sessionId)
        }
    }

    @Test
    suspend fun `logOut returns Unauthenticated when not given a session ID`() {
        withMigratedDataStore { dataStore ->
            val api = makeAuthenticationApi(dataStore)

            val response = api.logOut(None)

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `logOut returns Unauthenticated when session does not exist`() {
        withMigratedDataStore { dataStore ->
            val api = makeAuthenticationApi(dataStore)

            val response = api.logOut(Some("session1"))

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `logOut returns Unauthenticated when session is expired`() {
        withMigratedDataStore { dataStore ->
            val signInTime = FixedTimestampSource().now()
            val sessionId = makeAuthenticationApi(dataStore, FixedTimestampSource(signInTime))
                .createSession(ExternalUserId.Github(1))
                .unwrap()
                .sessionId
            val api = makeAuthenticationApi(dataStore, FixedTimestampSource(signInTime.plusDays(2)))

            val response = api.logOut(Some(sessionId))

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `logOut invalidates session when session is active`() {
        withMigratedDataStore { dataStore ->
            val api = makeAuthenticationApi(dataStore)
            val sessionId = api.createSession(ExternalUserId.Github(1)).unwrap().sessionId

            api.logOut(Some(sessionId)).unwrap()
            val nextResponse = api.logOut(Some(sessionId))

            assertInstanceOf<UnauthenticatedError>(nextResponse.unwrapErr())
        }
    }

    private companion object {
        private val vertx = Vertx.vertx()

        @AfterAll
        @JvmStatic
        fun closeVertx() {
            vertx.close().await()
        }

        private suspend fun withMigratedDataStore(block: suspend (DataStore) -> Unit) {
            val dataStore = MemoryDataStore(vertx)
            try {
                dataStore.migrateToHead().unwrap()
                block(dataStore)
            } finally {
                dataStore.close().unwrap()
            }
        }

        private fun makeAuthenticationApi(
            dataStore: DataStore,
            timestampSource: TimestampSource = FixedTimestampSource(),
        ): AuthenticationApi {
            return AuthenticationApiImpl(
                dataStore = dataStore,
                idGenerator = IdGenerator(DeterministicRandomSource()),
                sessionLifetime = SessionLifetime.parse("24h").unwrap(),
                timestampSource = timestampSource,
            )
        }
    }
}
