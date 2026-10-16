// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console2

import app.accrescent.server.UNIX_EPOCH
import app.accrescent.server.adapters.driven.datastore2.vertx.MemoryDataStore
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.core.NonNegativeLong
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrap2
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
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfResponse
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console2.Organization
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import app.accrescent.server.domain.ports.driving.api.console2.User
import app.accrescent.server.domain.ports.driving.api.console2.UserRole
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
                GetMyOrganizationResponse("org_1uMy9o9BqyoxomLjIEbctU"),
                response.unwrap(),
            )
        }
    }

    @Test
    suspend fun `getOrganization returns InsufficientPermission for unauthorized request`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
            }
                .unwrap2()
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.getOrganization(context, GetOrganizationRequest("org2"))

            assertInstanceOf<InsufficientPermissionError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `getOrganization returns organization for authorized request`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.getOrganization(
                context,
                GetOrganizationRequest("org_1uMy9o9BqyoxomLjIEbctU"),
            )

            assertEquals(
                GetOrganizationResponse(
                    Organization(
                        id = "org_1uMy9o9BqyoxomLjIEbctU",
                        publishedAppLimit = NonNegativeLong.new(1).unwrap(),
                        publishedAppCount = NonNegativeLong.MIN_VALUE,
                        activeAppDraftLimit = NonNegativeLong.new(3).unwrap(),
                        activeAppDraftCount = NonNegativeLong.MIN_VALUE,
                    ),
                ),
                response.unwrap(),
            )
        }
    }

    @Test
    suspend fun `getSelf returns Unauthenticated for unauthenticated request`() {
        withMigratedDataStore { dataStore ->
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.getSelf(CallContext(None))

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `getSelf returns user with no roles when user is not the admin`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
            }
                .unwrap2()
            val consoleApi = makeConsoleApi(dataStore, admin = ExternalUserId.Github(2))

            val response = consoleApi.getSelf(context)

            assertEquals(
                GetSelfResponse(User("u_7Vg3AgHSuhCI6g3btbAESz", emptySet())),
                response.unwrap(),
            )
        }
    }

    @Test
    suspend fun `getSelf returns user with reviewer and publisher roles when user is the admin`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.getSelf(context)

            assertEquals(
                GetSelfResponse(
                    User(
                        id = "u_7Vg3AgHSuhCI6g3btbAESz",
                        roles = setOf(UserRole.REVIEWER, UserRole.PUBLISHER),
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
            admin: ExternalUserId = ExternalUserId.Github(1),
        ): ConsoleApi {
            return ConsoleApiImpl(dataStore, timestampSource, admin)
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
