// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console

import app.accrescent.server.UNIX_EPOCH
import app.accrescent.server.adapters.driven.datastore.jdbc.InMemoryDataStore
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrap2
import app.accrescent.server.core.unwrapErr
import app.accrescent.server.domain.appstore.ListingLanguage
import app.accrescent.server.domain.ports.driven.datastore.AppListing
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.console.AppApi
import app.accrescent.server.domain.ports.driving.api.console.CallContext
import app.accrescent.server.domain.ports.driving.api.console.GetAppRequest
import app.accrescent.server.domain.ports.driving.api.console.GetAppResponse
import app.accrescent.server.domain.ports.driving.api.console.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console.UnauthenticatedError
import app.accrescent.server.domain.ports.driving.api.console.UpdateAppRequest
import arrow.core.Either
import arrow.core.None
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.days
import app.accrescent.server.domain.ports.driven.datastore.App as DataApp
import app.accrescent.server.domain.ports.driving.api.console.App as ApiApp

class AppApiImplTest {
    @Test
    fun `getApp returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appApi = makeAppApi(dataStore)

            val response = appApi.getApp(context, GetAppRequest("app1"))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `getApp returns app for authorized request for existing app`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val originalApp = makeApp(organizationId = getMyOrganizationId(dataStore, context))
            dataStore.runTxWithRetry { tx ->
                tx.apps.saveWithDefaultListing(
                    originalApp,
                    AppListing("appListing1", "app1", ListingLanguage.EN_US)
                ).bind()
            }.unwrap2()
            val appApi = makeAppApi(dataStore)

            val response = appApi.getApp(context, GetAppRequest("app1"))

            assertEquals(
                GetAppResponse(
                    ApiApp(
                        id = originalApp.id,
                        defaultAppListingId = originalApp.defaultAppListingId,
                        publiclyListed = originalApp.publiclyListed,
                    )
                )
                    .right(),
                response,
            )
        }
    }

    @Test
    fun `updateApp returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appApi = makeAppApi(dataStore)

            val response = appApi.updateApp(context, UpdateAppRequest("app1", false))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `updateApp returns successfully for authorized request to existing app`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            dataStore.runTxWithRetry { tx ->
                tx.apps.saveWithDefaultListing(
                    makeApp(organizationId = getMyOrganizationId(dataStore, context)),
                    AppListing("appListing1", "app1", ListingLanguage.EN_US)
                ).bind()
            }.unwrap2()
            val appApi = makeAppApi(dataStore)

            val response = appApi.updateApp(context, UpdateAppRequest("app1", false))

            assertEquals(Unit.right(), response)
        }
    }

    @Test
    fun `updateApp modifies app's publiclyListed attribute when masked`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            dataStore.runTxWithRetry { tx ->
                tx.apps.saveWithDefaultListing(
                    makeApp(organizationId = getMyOrganizationId(dataStore, context)),
                    AppListing("appListing1", "app1", ListingLanguage.EN_US)
                ).bind()
            }.unwrap2()
            val appApi = makeAppApi(dataStore)

            appApi
                .updateApp(context, UpdateAppRequest(appId = "app1", publiclyListed = true))
                .unwrap()
            val response = appApi.getApp(context, GetAppRequest("app1")).unwrap()

            assertTrue(response.app.publiclyListed)
        }
    }

    @ParameterizedTest
    @MethodSource("unauthenticatedCallTestCases")
    fun `API methods return Unauthenticated for calls without an active session`(
        testCase: UnauthenticatedCallTestCase,
    ) {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val expiredContext = signIn(dataStore, sessionLifetime = 1.days)
            // Advance the API past the session's lifetime so the session created above is expired
            // for every call made through it
            val appApi = makeAppApi(
                dataStore,
                timestampSource = FixedTimestampSource(UNIX_EPOCH.plusDays(2)),
            )
            val context = when (testCase.session) {
                UnauthenticatedCallTestCase.Session.NONE -> CallContext(None)
                UnauthenticatedCallTestCase.Session.EXPIRED -> expiredContext
            }

            val response = testCase.call(appApi, context)

            assertEquals(UnauthenticatedError, response.unwrapErr())
        }
    }

    private fun makeAppApi(
        dataStore: DataStore,
        timestampSource: TimestampSource = FixedTimestampSource(),
    ): AppApi {
        return AppApiImpl(dataStore, timestampSource)
    }

    private fun makeApp(
        organizationId: String,
        id: String = "app1",
        defaultAppListingId: String = "appListing1",
        publiclyListed: Boolean = false,
    ) = DataApp(id, organizationId, defaultAppListingId, publiclyListed)

    companion object {
        data class UnauthenticatedCallTestCase(
            val method: String,
            val session: Session,
            val call: (AppApi, CallContext) -> Either<*, *>,
        ) {
            enum class Session { NONE, EXPIRED }

            override fun toString(): String = "$method, $session"
        }

        @JvmStatic
        private fun unauthenticatedCallTestCases(): List<UnauthenticatedCallTestCase> {
            val calls: List<Pair<String, (AppApi, CallContext) -> Either<*, *>>> = listOf(
                "getApp" to { api, context -> api.getApp(context, GetAppRequest("app1")) },
                "updateApp" to { api, context ->
                    api.updateApp(context, UpdateAppRequest("app1", false))
                },
            )

            return calls.flatMap { (method, call) ->
                UnauthenticatedCallTestCase.Session.entries.map { session ->
                    UnauthenticatedCallTestCase(method, session, call)
                }
            }
        }
    }
}
