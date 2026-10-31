// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console2

import app.accrescent.server.UNIX_EPOCH
import app.accrescent.server.adapters.driven.datastore2.vertx.MemoryDataStore
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.PositiveInt
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrap2
import app.accrescent.server.core.unwrapErr
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.api.authn.AuthenticationApiImpl
import app.accrescent.server.domain.appstore.AppName
import app.accrescent.server.domain.appstore.AppShortDescription
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.config.SessionLifetime
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.console2.ActiveAppDraftLimitExceededError
import app.accrescent.server.domain.ports.driving.api.console2.AppDraftListingText
import app.accrescent.server.domain.ports.driving.api.console2.AppDraftListingTextAlreadyExistsError
import app.accrescent.server.domain.ports.driving.api.console2.AppDraftSummary
import app.accrescent.server.domain.ports.driving.api.console2.CallContext
import app.accrescent.server.domain.ports.driving.api.console2.ConsoleApi
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftListingTextRequest
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console2.DeleteAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetAppDraftDetailsRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetAppDraftDetailsResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfResponse
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console2.InvalidPageTokenError
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesRequest
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesResponse
import app.accrescent.server.domain.ports.driving.api.console2.Organization
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import app.accrescent.server.domain.ports.driving.api.console2.User
import app.accrescent.server.domain.ports.driving.api.console2.UserRole
import app.accrescent.server.impl.v1.ListAppDraftSummariesPageToken
import app.accrescent.server.impl.v1.listAppDraftSummariesPageToken
import arrow.core.None
import arrow.core.Some
import com.google.protobuf.timestamp
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.io.encoding.Base64

class ConsoleApiImplTest {
    @Test
    suspend fun `createAppDraft returns Unauthenticated for unauthenticated request`() {
        withMigratedDataStore { dataStore ->
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.createAppDraft(
                CallContext(None),
                CreateAppDraftRequest("org_1uMy9o9BqyoxomLjIEbctU"),
            )

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `createAppDraft returns InsufficientPermission for unauthorized request`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
            }
                .unwrap2()
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.createAppDraft(context, CreateAppDraftRequest("org2"))

            assertInstanceOf<InsufficientPermissionError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `createAppDraft returns ActiveAppDraftLimitExceeded when attempting to create more than 3 app drafts in org`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)
            val request = CreateAppDraftRequest("org_1uMy9o9BqyoxomLjIEbctU")
            repeat(3) { consoleApi.createAppDraft(context, request).unwrap() }

            val response = consoleApi.createAppDraft(context, request)

            assertInstanceOf<ActiveAppDraftLimitExceededError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `createAppDraftListingText returns Unauthenticated for unauthenticated request`() {
        withMigratedDataStore { dataStore ->
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.createAppDraftListingText(
                CallContext(None),
                CreateAppDraftListingTextRequest("draft1", APP_NAME, SHORT_DESCRIPTION),
            )

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `createAppDraftListingText returns InsufficientPermission for unauthorized request`() {
        withMigratedDataStore { dataStore ->
            val idGenerator = IdGenerator(DeterministicRandomSource())
            val context = signIn(dataStore, idGenerator = idGenerator)
            val otherContext = signIn(
                dataStore = dataStore,
                externalUserId = ExternalUserId.Github(2),
                idGenerator = idGenerator,
            )
            val consoleApi = makeConsoleApi(dataStore)
            val otherOrganizationId = consoleApi
                .getMyOrganization(otherContext)
                .unwrap()
                .organizationId
            val otherAppDraftId = consoleApi
                .createAppDraft(otherContext, CreateAppDraftRequest(otherOrganizationId))
                .unwrap()
                .appDraftId

            val response = consoleApi.createAppDraftListingText(
                context,
                CreateAppDraftListingTextRequest(otherAppDraftId, APP_NAME, SHORT_DESCRIPTION),
            )

            assertInstanceOf<InsufficientPermissionError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `createAppDraftListingText returns AppDraftListingTextAlreadyExists when app draft already has listing text`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)
            val appDraftId = consoleApi
                .createAppDraft(context, CreateAppDraftRequest("org_1uMy9o9BqyoxomLjIEbctU"))
                .unwrap()
                .appDraftId
            val request = CreateAppDraftListingTextRequest(appDraftId, APP_NAME, SHORT_DESCRIPTION)
            consoleApi.createAppDraftListingText(context, request).unwrap()

            val response = consoleApi.createAppDraftListingText(context, request)

            assertInstanceOf<AppDraftListingTextAlreadyExistsError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `deleteAppDraft returns Unauthenticated for unauthenticated request`() {
        withMigratedDataStore { dataStore ->
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.deleteAppDraft(
                CallContext(None),
                DeleteAppDraftRequest("draft_1uMy9o9BqyoxomLjIEbctU"),
            )

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `deleteAppDraft returns InsufficientPermission for unauthorized request`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
                tx.createAppDraft("draft2", "org2", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.deleteAppDraft(context, DeleteAppDraftRequest("draft2"))

            assertInstanceOf<InsufficientPermissionError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `deleteAppDraft deletes app draft for authorized request`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)
            val organizationId = "org_1uMy9o9BqyoxomLjIEbctU"
            val appDraftId = consoleApi
                .createAppDraft(context, CreateAppDraftRequest(organizationId))
                .unwrap()
                .appDraftId

            consoleApi.deleteAppDraft(context, DeleteAppDraftRequest(appDraftId)).unwrap()
            val response = consoleApi.getOrganization(context, GetOrganizationRequest(organizationId))

            assertEquals(
                NonNegativeInt.MIN_VALUE,
                response.unwrap().organization.activeAppDraftCount,
            )
        }
    }

    @Test
    suspend fun `getAppDraftDetails returns Unauthenticated for unauthenticated request`() {
        withMigratedDataStore { dataStore ->
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.getAppDraftDetails(
                CallContext(None),
                GetAppDraftDetailsRequest("draft1"),
            )

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `getAppDraftDetails returns InsufficientPermission for unauthorized request`() {
        withMigratedDataStore { dataStore ->
            val idGenerator = IdGenerator(DeterministicRandomSource())
            val context = signIn(dataStore, idGenerator = idGenerator)
            val otherContext = signIn(
                dataStore = dataStore,
                externalUserId = ExternalUserId.Github(2),
                idGenerator = idGenerator,
            )
            val consoleApi = makeConsoleApi(dataStore)
            val otherOrganizationId = consoleApi
                .getMyOrganization(otherContext)
                .unwrap()
                .organizationId
            val otherAppDraftId = consoleApi
                .createAppDraft(otherContext, CreateAppDraftRequest(otherOrganizationId))
                .unwrap()
                .appDraftId

            val response = consoleApi.getAppDraftDetails(
                context,
                GetAppDraftDetailsRequest(otherAppDraftId),
            )

            assertInstanceOf<InsufficientPermissionError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `getAppDraftDetails returns no listing text for app draft without listing text`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)
            val appDraftId = consoleApi
                .createAppDraft(context, CreateAppDraftRequest("org_1uMy9o9BqyoxomLjIEbctU"))
                .unwrap()
                .appDraftId

            val response = consoleApi.getAppDraftDetails(
                context,
                GetAppDraftDetailsRequest(appDraftId),
            )

            assertEquals(GetAppDraftDetailsResponse(None), response.unwrap())
        }
    }

    @Test
    suspend fun `getAppDraftDetails returns listing text for app draft with listing text`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)
            val appDraftId = consoleApi
                .createAppDraft(context, CreateAppDraftRequest("org_1uMy9o9BqyoxomLjIEbctU"))
                .unwrap()
                .appDraftId
            val etag = consoleApi
                .createAppDraftListingText(
                    context,
                    CreateAppDraftListingTextRequest(appDraftId, APP_NAME, SHORT_DESCRIPTION),
                )
                .unwrap()
                .etag

            val response =
                consoleApi.getAppDraftDetails(context, GetAppDraftDetailsRequest(appDraftId))

            assertEquals(
                GetAppDraftDetailsResponse(
                    Some(AppDraftListingText(APP_NAME.value, SHORT_DESCRIPTION.value, etag)),
                ),
                response.unwrap(),
            )
        }
    }

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
                        publishedAppLimit = NonNegativeInt.new(1).unwrap(),
                        publishedAppCount = NonNegativeInt.MIN_VALUE,
                        activeAppDraftLimit = NonNegativeInt.new(3).unwrap(),
                        activeAppDraftCount = NonNegativeInt.MIN_VALUE,
                    ),
                ),
                response.unwrap(),
            )
        }
    }

    @Test
    suspend fun `getOrganization returns organization's accurate active app draft count`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)
            val organizationId = "org_1uMy9o9BqyoxomLjIEbctU"
            repeat(2) {
                consoleApi.createAppDraft(context, CreateAppDraftRequest(organizationId)).unwrap()
            }

            val response = consoleApi.getOrganization(context, GetOrganizationRequest(organizationId))

            assertEquals(
                NonNegativeInt.new(2).unwrap(),
                response.unwrap().organization.activeAppDraftCount,
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

    @Test
    suspend fun `listAppDraftSummaries returns Unauthenticated for unauthenticated request`() {
        withMigratedDataStore { dataStore ->
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.listAppDraftSummaries(
                CallContext(None),
                ListAppDraftSummariesRequest("org_1uMy9o9BqyoxomLjIEbctU", None, None),
            )

            assertInstanceOf<UnauthenticatedError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `listAppDraftSummaries returns InsufficientPermission for unauthorized request`() {
        withMigratedDataStore { dataStore ->
            val idGenerator = IdGenerator(DeterministicRandomSource())
            val context = signIn(dataStore, idGenerator = idGenerator)
            val otherContext = signIn(
                dataStore = dataStore,
                externalUserId = ExternalUserId.Github(2),
                idGenerator = idGenerator,
            )
            val consoleApi = makeConsoleApi(dataStore)
            val otherOrganizationId = consoleApi
                .getMyOrganization(otherContext)
                .unwrap()
                .organizationId

            val response = consoleApi.listAppDraftSummaries(
                context,
                ListAppDraftSummariesRequest(otherOrganizationId, None, None),
            )

            assertInstanceOf<InsufficientPermissionError>(response.unwrapErr())
        }
    }

    @ParameterizedTest
    @MethodSource("invalidListAppDraftSummariesPageTokens")
    suspend fun `listAppDraftSummaries returns InvalidPageToken for invalid page token`(
        pageToken: String,
    ) {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)

            val response = consoleApi.listAppDraftSummaries(
                context,
                ListAppDraftSummariesRequest("org_1uMy9o9BqyoxomLjIEbctU", None, Some(pageToken)),
            )

            assertInstanceOf<InvalidPageTokenError>(response.unwrapErr())
        }
    }

    @Test
    suspend fun `listAppDraftSummaries returns next page token when page is full`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)
            val organizationId = "org_1uMy9o9BqyoxomLjIEbctU"
            consoleApi.createAppDraft(context, CreateAppDraftRequest(organizationId)).unwrap()

            val response = consoleApi.listAppDraftSummaries(
                context,
                ListAppDraftSummariesRequest(
                    organizationId = organizationId,
                    pageSize = Some(PositiveInt.new(1).unwrap()),
                    pageToken = None,
                ),
            )

            assertTrue(response.unwrap().nextPageToken.isSome())
        }
    }

    @Test
    suspend fun `listAppDraftSummaries returns next page when given valid page token`() {
        withMigratedDataStore { dataStore ->
            val context = signIn(dataStore)
            val consoleApi = makeConsoleApi(dataStore)
            val organizationId = "org_1uMy9o9BqyoxomLjIEbctU"
            val appDraftIds = List(3) {
                consoleApi
                    .createAppDraft(context, CreateAppDraftRequest(organizationId))
                    .unwrap()
                    .appDraftId
            }
            val pageSize = Some(PositiveInt.new(2).unwrap())
            val firstPage = consoleApi
                .listAppDraftSummaries(
                    context,
                    ListAppDraftSummariesRequest(organizationId, pageSize, None),
                )
                .unwrap()
            val remainingAppDraftId =
                (appDraftIds - firstPage.appDraftSummaries.map { it.id }.toSet()).single()

            val response = consoleApi.listAppDraftSummaries(
                context,
                ListAppDraftSummariesRequest(organizationId, pageSize, firstPage.nextPageToken),
            )

            assertEquals(
                ListAppDraftSummariesResponse(
                    appDraftSummaries = listOf(
                        AppDraftSummary(remainingAppDraftId, None, None, None),
                    ),
                    nextPageToken = None,
                ),
                response.unwrap(),
            )
        }
    }

    private companion object {
        private val vertx = Vertx.vertx()
        private val APP_NAME = AppName.fromString("SecureChat").unwrap()
        private val SHORT_DESCRIPTION = AppShortDescription.fromString("Secure texting").unwrap()

        @JvmStatic
        private fun invalidListAppDraftSummariesPageTokens(): List<String> {
            val validCreateTime = timestamp { seconds = 0 }

            return listOf(
                // Invalid base64
                "!",
                // Invalid protobuf
                Base64.UrlSafe.encode(byteArrayOf(-1)),
                // Missing create time
                encodePageToken(listAppDraftSummariesPageToken { lastAppDraftId = "draft1" }),
                // Missing app draft ID
                encodePageToken(
                    listAppDraftSummariesPageToken { lastAppDraftCreateTime = validCreateTime },
                ),
                // Create time before 0001-01-01T00:00:00Z
                encodePageToken(
                    listAppDraftSummariesPageToken {
                        lastAppDraftCreateTime = timestamp { seconds = -62_135_596_801 }
                        lastAppDraftId = "draft1"
                    },
                ),
                // Create time after 9999-12-31T23:59:59.999999999Z
                encodePageToken(
                    listAppDraftSummariesPageToken {
                        lastAppDraftCreateTime = timestamp { seconds = 253_402_300_800 }
                        lastAppDraftId = "draft1"
                    },
                ),
                // Negative nanos
                encodePageToken(
                    listAppDraftSummariesPageToken {
                        lastAppDraftCreateTime = timestamp { nanos = -1 }
                        lastAppDraftId = "draft1"
                    },
                ),
                // Nanos more than 999,999,999
                encodePageToken(
                    listAppDraftSummariesPageToken {
                        lastAppDraftCreateTime = timestamp { nanos = 1_000_000_000 }
                        lastAppDraftId = "draft1"
                    }
                ),
            )
        }

        private fun encodePageToken(token: ListAppDraftSummariesPageToken): String {
            return Base64.UrlSafe.encode(token.toByteArray())
        }

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
            return ConsoleApiImpl(
                dataStore = dataStore,
                idGenerator = IdGenerator(DeterministicRandomSource()),
                timestampSource = timestampSource,
                adminExternalUserId = admin,
            )
        }

        /**
         * Signs a new user up, creates a login session, and returns the necessary context to make
         * calls as the new user.
         */
        private suspend fun signIn(
            dataStore: DataStore,
            timestampSource: TimestampSource = FixedTimestampSource(),
            externalUserId: ExternalUserId = ExternalUserId.Github(1),
            idGenerator: IdGenerator = IdGenerator(DeterministicRandomSource()),
        ): CallContext {
            val authenticationApi = AuthenticationApiImpl(
                dataStore = dataStore,
                idGenerator = idGenerator,
                sessionLifetime = SessionLifetime.parse("24h").unwrap(),
                timestampSource = timestampSource,
            )
            val sessionId = authenticationApi.createSession(externalUserId).unwrap().sessionId

            return CallContext(Some(sessionId))
        }
    }
}
