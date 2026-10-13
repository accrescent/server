// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console2

import app.accrescent.server.adapters.driven.datastore2.vertx.MemoryDataStore
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrapErr
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.api.authn.AuthenticationApiImpl
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.config.SessionLifetime
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.console2.CallContext
import app.accrescent.server.domain.ports.driving.api.console2.ConsoleApi
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.Organization
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import arrow.core.None
import arrow.core.Some
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class ConsoleApiImplTest {
    @Test
    suspend fun `getMyOrganization returns Unauthenticated when not given a session ID`() {
        withMigratedDataStore { dataStore ->
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.getMyOrganization(CallContext(None))

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `getMyOrganization returns Unauthenticated when session does not exist`() {
        withMigratedDataStore { dataStore ->
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.getMyOrganization(CallContext(Some("session1")))

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `getMyOrganization returns Unauthenticated when session is expired`() {
        withMigratedDataStore { dataStore ->
            val signInTime = FixedTimestampSource().now()
            val context = signIn(dataStore, timestampSource = FixedTimestampSource(signInTime))
            val consoleApi = makeConsoleApi(
                dataStore = dataStore,
                timestampSource = FixedTimestampSource(signInTime.plusDays(2)),
            )

            val response = consoleApi.getMyOrganization(context)

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `getMyOrganization returns user's organization when authenticated`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.getMyOrganization(context)

            assertEquals(
                GetMyOrganizationResponse(
                    Organization(
                        id = "org_1uMy9o9BqyoxomLjIEbctU",
                        publishedAppLimit = 1u,
                        publishedAppCount = 0u,
                        activeAppDraftLimit = 3u,
                        activeAppDraftCount = 0u,
                    ),
                ),
                response.unwrap(),
            )
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

        private fun makeConsoleApi(
            dataStore: DataStore,
            timestampSource: TimestampSource = FixedTimestampSource(),
        ): ConsoleApi {
            return ConsoleApiImpl(dataStore, timestampSource)
        }

        /**
         * Signs a new user up, creates a login session, and returns the necessary context to make
         * calls as the new user.
         */
        private suspend fun signIn(
            dataStore: DataStore,
            timestampSource: TimestampSource = FixedTimestampSource(),
        ): CallContext {
            val authenticationApi = AuthenticationApiImpl(
                dataStore = dataStore,
                idGenerator = IdGenerator(DeterministicRandomSource()),
                sessionLifetime = SessionLifetime.parse("24h").unwrap(),
                timestampSource = timestampSource,
            )
            val sessionId =
                authenticationApi.createSession(ExternalUserId.Github(1)).unwrap().sessionId

            return CallContext(Some(sessionId))
        }
    }
}
