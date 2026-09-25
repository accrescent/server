// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.api.console

import app.accrescent.server.parcelo.UNIX_EPOCH
import app.accrescent.server.parcelo.adapters.driven.blobstorage.LocalBlobStorage
import app.accrescent.server.parcelo.adapters.driven.blobstorage.LocalOnlyBlobStorage
import app.accrescent.server.parcelo.adapters.driven.datastore.jdbc.InMemoryDataStore
import app.accrescent.server.parcelo.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.parcelo.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.parcelo.appPackage
import app.accrescent.server.parcelo.core.unwrap
import app.accrescent.server.parcelo.core.unwrap2
import app.accrescent.server.parcelo.core.unwrapErr
import app.accrescent.server.parcelo.createAppDraftListing
import app.accrescent.server.parcelo.domain.android.ApplicationId
import app.accrescent.server.parcelo.domain.android.SdkVersion
import app.accrescent.server.parcelo.domain.android.VersionCode
import app.accrescent.server.parcelo.domain.android.VersionName
import app.accrescent.server.parcelo.domain.appstore.ListingLanguage
import app.accrescent.server.parcelo.domain.authn.ExternalUserId
import app.accrescent.server.parcelo.domain.ports.driven.datastore.App
import app.accrescent.server.parcelo.domain.ports.driven.datastore.AppListing
import app.accrescent.server.parcelo.domain.ports.driven.datastore.DataStore
import app.accrescent.server.parcelo.domain.ports.driven.randomsource.RandomSource
import app.accrescent.server.parcelo.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.parcelo.domain.ports.driving.api.console.ActiveAppDraftLimitExceededError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraftApi
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraftHasNoDefaultListingError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraftHasNoPackageError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraftListingAlreadyExistsError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraftListingNotFoundError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraftPackageNotFoundError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraftSubmittedError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraftSubmittedForAppIdError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.CallContext
import app.accrescent.server.parcelo.domain.ports.driving.api.console.CreateAppDraftListingRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.CreateAppDraftRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.DeleteAppDraftListingRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.DeleteAppDraftRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.DownloadAppDraftRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.DownloadAppDraftResponse
import app.accrescent.server.parcelo.domain.ports.driving.api.console.GetAppDraftListingRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.GetAppDraftListingResponse
import app.accrescent.server.parcelo.domain.ports.driving.api.console.GetAppDraftRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.GetAppDraftResponse
import app.accrescent.server.parcelo.domain.ports.driving.api.console.InsufficientPermissionError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.ListAppDraftListingsRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.ListAppDraftsRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.ListAppDraftsResponse
import app.accrescent.server.parcelo.domain.ports.driving.api.console.PublishedAppLimitExceededError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.SubmitAppDraftRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.UnauthenticatedError
import app.accrescent.server.parcelo.domain.ports.driving.api.console.UpdateAppDraftListingRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.UpdateAppDraftRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.UploadAppDraftListingIconRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.UploadAppDraftListingIconResponse
import app.accrescent.server.parcelo.domain.ports.driving.api.console.UploadAppDraftRequest
import app.accrescent.server.parcelo.domain.ports.driving.api.console.UploadAppDraftResponse
import app.accrescent.server.parcelo.domain.uri.HttpUri
import app.accrescent.server.parcelo.saveAppPackageFromNewUpload
import app.accrescent.server.parcelo.signInNewUser
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.days
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraft as ApiAppDraft
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppDraftListing as ApiAppDraftListing
import app.accrescent.server.parcelo.domain.ports.driving.api.console.AppPackage as ApiAppPackage

class AppDraftApiImplTest {
    @Test
    fun `createAppDraft returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.createAppDraft(context, CreateAppDraftRequest("org2"))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `createAppDraft returns ActiveAppDraftLimitExceeded when attempting to exceed org active app draft limit`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val organizationId = getMyOrganizationId(dataStore, context)
            val appDraftApi = makeAppDraftApi(dataStore)
            repeat(3) {
                appDraftApi.createAppDraft(context, CreateAppDraftRequest(organizationId)).unwrap()
            }

            val response =
                appDraftApi.createAppDraft(context, CreateAppDraftRequest(organizationId))

            assertEquals(ActiveAppDraftLimitExceededError(3uL), response.unwrapErr())
        }
    }

    @Test
    fun `downloadAppDraft returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val response =
                appDraftApi.downloadAppDraft(context, DownloadAppDraftRequest("appDraft1"))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `downloadAppDraft returns AppDraftPackageNotFound when app draft exists without package`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)

            val response =
                appDraftApi.downloadAppDraft(context, DownloadAppDraftRequest(appDraftId))

            assertEquals(AppDraftPackageNotFoundError(appDraftId), response.unwrapErr())
        }
    }

    @Test
    fun `downloadAppDraft returns successfully when permitted`() {
        val randomSource = DeterministicRandomSource()
        InMemoryDataStore(randomSource).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
            }
                .unwrap2()
            val blobStorage = LocalBlobStorage(randomSource)
            val appDraftApi = makeAppDraftApi(dataStore, blobStorage = blobStorage)

            val response = appDraftApi
                .downloadAppDraft(CallContext(Some("session1")), DownloadAppDraftRequest("appDraft1"))

            assertEquals(
                DownloadAppDraftResponse(
                    HttpUri
                        .fromString(
                            "http://localhost:${blobStorage.port}/download/" +
                                    "bb20b45f-d4d9-5138-3d93-cb799b3970be"
                        )
                        .unwrap()
                )
                    .right(),
                response,
            )
        }
    }

    @Test
    fun `getAppDraft returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.getAppDraft(context, GetAppDraftRequest("appDraft1"))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `getAppDraft returns app draft created by createAppDraft`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val organizationId = getMyOrganizationId(dataStore, context)
            val appDraftApi = makeAppDraftApi(dataStore)

            val createResponse = appDraftApi
                .createAppDraft(context, CreateAppDraftRequest(organizationId))
                .unwrap()
            val getResponse =
                appDraftApi.getAppDraft(context, GetAppDraftRequest(createResponse.appDraftId))

            assertEquals(
                GetAppDraftResponse(
                    ApiAppDraft.Unsubmitted(
                        id = "ad_2wTa5P82Lwqd50UvNyQRad",
                        createTime = UNIX_EPOCH,
                        defaultAppDraftListingId = None,
                        appPackage = None,
                    )
                ).right(),
                getResponse,
            )
        }
    }

    @Test
    fun `getAppDraft returns correct fully populated app draft`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi
                .getAppDraft(CallContext(Some("session1")), GetAppDraftRequest("appDraft1"))

            assertEquals(
                GetAppDraftResponse(
                    ApiAppDraft.Submitted(
                        id = "appDraft1",
                        createTime = UNIX_EPOCH,
                        defaultAppDraftListingId = "appDraftListing1",
                        appPackage = ApiAppPackage(
                            androidApplicationId = ApplicationId.fromString("com.example.app").unwrap(),
                            versionCode = VersionCode.fromInt(1).unwrap(),
                            versionName = VersionName.fromString("1.0").unwrap(),
                            targetSdk = SdkVersion.fromInt(37).unwrap(),
                        ),
                        submitTime = UNIX_EPOCH,
                    )
                ).right(),
                response,
            )
        }
    }

    @Test
    fun `listAppDrafts returns app drafts from only requested organization`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val timestampSource = FixedTimestampSource()
            val randomSource = DeterministicRandomSource()
            val context1 = signIn(dataStore, randomSource, ExternalUserId.Github(1))
            val context2 = signIn(dataStore, randomSource, ExternalUserId.Github(2))
            val organizationId1 = getMyOrganizationId(dataStore, context1)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId1 = createAppDraft(dataStore, appDraftApi, context1)
            createAppDraft(dataStore, appDraftApi, context2)

            val response = appDraftApi
                .listAppDrafts(context1, ListAppDraftsRequest(organizationId1, 2u, None))
                .map { it.appDrafts }

            assertEquals(
                listOf(
                    ApiAppDraft.Unsubmitted(
                        id = appDraftId1,
                        createTime = timestampSource.now(),
                        defaultAppDraftListingId = None,
                        appPackage = None,
                    )
                ).right(),
                response,
            )
        }
    }

    @Test
    fun `listAppDrafts returns correct fully populated app drafts`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi
                .listAppDrafts(CallContext(Some("session1")), ListAppDraftsRequest("org1", 1u, None))
                .map { it.appDrafts }

            assertEquals(
                listOf(
                    ApiAppDraft.Submitted(
                        id = "appDraft1",
                        createTime = UNIX_EPOCH,
                        defaultAppDraftListingId = "appDraftListing1",
                        appPackage = ApiAppPackage(
                            androidApplicationId = ApplicationId.fromString("com.example.app").unwrap(),
                            versionCode = VersionCode.fromInt(1).unwrap(),
                            versionName = VersionName.fromString("1.0").unwrap(),
                            targetSdk = SdkVersion.fromInt(37).unwrap(),
                        ),
                        submitTime = UNIX_EPOCH,
                    )
                ).right(),
                response,
            )
        }
    }

    @Test
    fun `listAppDrafts returns only authorized app drafts`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val randomSource = DeterministicRandomSource()
            val context1 = signIn(dataStore, randomSource, ExternalUserId.Github(1))
            val context2 = signIn(dataStore, randomSource, ExternalUserId.Github(2))
            val organizationId1 = getMyOrganizationId(dataStore, context1)
            val appDraftApi = makeAppDraftApi(dataStore)
            createAppDraft(dataStore, appDraftApi, context1)

            val response = appDraftApi
                .listAppDrafts(context2, ListAppDraftsRequest(organizationId1, 1u, None))
                .map { it.appDrafts }

            assertEquals(emptyList<ApiAppDraft>().right(), response)
        }
    }

    @Test
    fun `listAppDrafts respects page size as maximum`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val organizationId = getMyOrganizationId(dataStore, context)
            val appDraftApi = makeAppDraftApi(dataStore)
            repeat(2) { createAppDraft(dataStore, appDraftApi, context) }

            val response = appDraftApi
                .listAppDrafts(context, ListAppDraftsRequest(organizationId, 1u, None))

            assertInstanceOf<Either.Right<ListAppDraftsResponse>>(response)
            assertEquals(1, response.value.appDrafts.size)
        }
    }

    @Test
    fun `listAppDrafts traverses all items when paginating`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val organizationId = getMyOrganizationId(dataStore, context)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftIds = List(2) { createAppDraft(dataStore, appDraftApi, context) }

            val allDrafts = mutableListOf<ApiAppDraft>()
            var pageToken: Option<String> = None
            do {
                val response = appDraftApi
                    .listAppDrafts(context, ListAppDraftsRequest(organizationId, 1u, pageToken))
                    .unwrap()
                allDrafts.addAll(response.appDrafts)
                pageToken = response.nextPageToken
            } while (pageToken.isSome())

            assertEquals(appDraftIds.toSet(), allDrafts.map { it.id }.toSet())
        }
    }

    @Test
    fun `listAppDrafts returns page token if items remain`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val organizationId = getMyOrganizationId(dataStore, context)
            val appDraftApi = makeAppDraftApi(dataStore)
            repeat(2) { createAppDraft(dataStore, appDraftApi, context) }

            val response = appDraftApi
                .listAppDrafts(context, ListAppDraftsRequest(organizationId, 1u, None))

            assertInstanceOf<Either.Right<ListAppDraftsResponse>>(response)
            assertTrue(response.value.nextPageToken.isSome())
        }
    }

    @Test
    fun `submitAppDraft returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.submitAppDraft(context, SubmitAppDraftRequest("appDraft1"))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `submitAppDraft returns AppDraftSubmitted when app draft is already submitted`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }.unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi
                .submitAppDraft(CallContext(Some("session1")), SubmitAppDraftRequest("appDraft1"))

            assertEquals(AppDraftSubmittedError("appDraft1"), response.unwrapErr())
        }
    }

    @Test
    fun `submitAppDraft returns AppDraftHasNoPackage when app draft does not have a package`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)

            val response = appDraftApi.submitAppDraft(context, SubmitAppDraftRequest(appDraftId))

            assertEquals(AppDraftHasNoPackageError(appDraftId), response.unwrapErr())
        }
    }

    @Test
    fun `submitAppDraft returns AppDraftHasNoDefaultListing when app draft does not have a default listing`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
            }.unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi
                .submitAppDraft(CallContext(Some("session1")), SubmitAppDraftRequest("appDraft1"))

            assertEquals(AppDraftHasNoDefaultListingError("appDraft1"), response.unwrapErr())
        }
    }

    @Test
    fun `submitAppDraft returns AppDraftListingsMissingIcons when not all app draft listings have an icon`() {
        // TODO
    }

    @Test
    fun `submitAppDraft returns AppDraftSubmittedForAppId when an app draft has already been submitted with the app ID`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(
                    tx,
                    appPackage = appPackage("appPackage1", appDraftId = "appDraft2"),
                    objectKey = "object1",
                )
                    .bind()
                createAppDraftListing(tx, "appDraftListing2", "appDraft2").bind()
                tx.appDrafts.updateDefaultListing("appDraft2", Some("appDraftListing2")).bind()
                tx.appDrafts.updateSubmitTime("appDraft2", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(
                    tx,
                    appPackage = appPackage(
                        "appPackage2",
                        appDraftId = "appDraft1",
                        externalBlobId = "blob2",
                    ),
                    pendingUploadId = "appDraftUpload2",
                    objectKey = "object2",
                )
                    .bind()
                createAppDraftListing(tx, "appDraftListing1", "appDraft1").bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }.unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi
                .submitAppDraft(CallContext(Some("session1")), SubmitAppDraftRequest("appDraft1"))

            assertEquals(AppDraftSubmittedForAppIdError("com.example.app"), response.unwrapErr())
        }
    }

    @Test
    fun `submitAppDraft returns PublishedAppLimitExceeded when the organization's published app limit is already reached`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.apps.saveWithDefaultListing(
                    App("app1", "org1", "appListing1", false),
                    AppListing("appListing1", "app1", ListingLanguage.EN_US),
                ).bind()
            }.unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi
                .submitAppDraft(CallContext(Some("session1")), SubmitAppDraftRequest("appDraft1"))

            assertEquals(PublishedAppLimitExceededError(1uL), response.unwrapErr())
        }
    }

    @Test
    fun `submitAppDraft submits app draft for valid request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val context = CallContext(Some("session1"))
            appDraftApi
                .submitAppDraft(context, SubmitAppDraftRequest("appDraft1"))
                .unwrap()
            val appDraft = appDraftApi
                .getAppDraft(context, GetAppDraftRequest("appDraft1"))
                .unwrap()
                .appDraft

            assertInstanceOf<ApiAppDraft.Submitted>(appDraft)
        }
    }

    @Test
    fun `uploadAppDraft returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.uploadAppDraft(context, UploadAppDraftRequest("appDraft1"))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `uploadAppDraft returns AppDraftSubmitted when app draft is already submitted`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi
                .uploadAppDraft(CallContext(Some("session1")), UploadAppDraftRequest("appDraft1"))

            assertEquals(AppDraftSubmittedError("appDraft1"), response.unwrapErr())
        }
    }

    @Test
    fun `uploadAppDraft returns successfully when permitted`() {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                val context = signIn(dataStore)
                val appDraftApi = makeAppDraftApi(dataStore, blobStorage = blobStorage)
                val appDraftId = createAppDraft(dataStore, appDraftApi, context)

                val response =
                    appDraftApi.uploadAppDraft(context, UploadAppDraftRequest(appDraftId))

                assertEquals(
                    UploadAppDraftResponse(
                        apkSetUploadUri = HttpUri
                            .fromString(
                                "http://localhost:${blobStorage.port}/upload/" +
                                        "bb20b45f-d4d9-5138-3d93-cb799b3970be"
                            )
                            .unwrap(),
                    )
                        .right(),
                    response,
                )
            }
        }
    }

    @Test
    fun `updateAppDraft returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.updateAppDraft(
                context,
                UpdateAppDraftRequest("appDraft1", "appDraftListing1"),
            )

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `updateAppDraft returns AppDraftSubmitted when app draft is already submitted`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.updateAppDraft(
                CallContext(Some("session1")),
                UpdateAppDraftRequest("appDraft1", "appDraftListing1"),
            )

            assertEquals(AppDraftSubmittedError("appDraft1"), response.unwrapErr())
        }
    }

    @Test
    fun `updateAppDraft returns AppDraftListingNotFound when new default listing ID does not exist`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)

            val response = appDraftApi.updateAppDraft(
                context,
                UpdateAppDraftRequest(appDraftId, "appDraftListing1"),
            )

            assertEquals(AppDraftListingNotFoundError("appDraftListing1"), response.unwrapErr())
        }
    }

    @Test
    fun `updateAppDraft updates default listing ID for valid request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)
            val appDraftListingId = createAppDraftListing(appDraftApi, context, appDraftId)

            appDraftApi
                .updateAppDraft(context, UpdateAppDraftRequest(appDraftId, appDraftListingId))
                .unwrap()
            val response = appDraftApi
                .getAppDraft(context, GetAppDraftRequest(appDraftId))
                .unwrap()

            assertEquals(
                Some(appDraftListingId),
                response.appDraft.optionalDefaultAppDraftListingId,
            )
        }
    }

    @Test
    fun `deleteAppDraft returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.deleteAppDraft(context, DeleteAppDraftRequest("appDraft1"))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `deleteAppDraft returns AppDraftSubmitted when app draft is already submitted`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi
                .deleteAppDraft(CallContext(Some("session1")), DeleteAppDraftRequest("appDraft1"))

            assertEquals(AppDraftSubmittedError("appDraft1"), response.unwrapErr())
        }
    }

    @Test
    fun `deleteAppDraft deletes app draft`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)

            appDraftApi.deleteAppDraft(context, DeleteAppDraftRequest(appDraftId)).unwrap()
            val response = appDraftApi.getAppDraft(context, GetAppDraftRequest(appDraftId))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `createAppDraftListing returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val request = CreateAppDraftListingRequest(
                appDraftId = "appDraft1",
                language = ListingLanguage.EN_US,
                name = "App Name",
                shortDescription = "App Short Description",
            )
            val response = appDraftApi.createAppDraftListing(context, request)

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `createAppDraftListing returns AppDraftListingAlreadyExists if listing exists for app draft with same language`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)
            createAppDraftListing(appDraftApi, context, appDraftId)

            val request = CreateAppDraftListingRequest(
                appDraftId = appDraftId,
                language = ListingLanguage.EN_US,
                name = "App Name",
                shortDescription = "App Short Description",
            )
            val response = appDraftApi.createAppDraftListing(context, request)

            assertEquals(
                AppDraftListingAlreadyExistsError(appDraftId, "en-US"),
                response.unwrapErr(),
            )
        }
    }

    @Test
    fun `createAppDraftListing returns AppDraftSubmitted if app draft has already been submitted`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val request = CreateAppDraftListingRequest(
                appDraftId = "appDraft1",
                language = ListingLanguage.EN_US,
                name = "App Name",
                shortDescription = "App Short Description",
            )
            val response = appDraftApi.createAppDraftListing(CallContext(Some("session1")), request)

            assertEquals(AppDraftSubmittedError("appDraft1"), response.unwrapErr())
        }
    }

    @Test
    fun `getAppDraftListing returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.getAppDraftListing(
                context,
                GetAppDraftListingRequest("appDraftListing1"),
            )

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `getAppDraftListing returns listing created by createAppDraftListing`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)

            val createRequest =
                CreateAppDraftListingRequest(appDraftId, ListingLanguage.EN_US, "name", "desc")
            val createResponse =
                appDraftApi.createAppDraftListing(context, createRequest).unwrap()
            val getResponse = appDraftApi.getAppDraftListing(
                context,
                GetAppDraftListingRequest(createResponse.appDraftListingId),
            )
                .unwrap()

            assertEquals(
                GetAppDraftListingResponse(
                    ApiAppDraftListing(
                        id = createResponse.appDraftListingId,
                        appDraftId = appDraftId,
                        language = "en-US",
                        name = "name",
                        shortDescription = "desc",
                    )
                ),
                getResponse,
            )
        }
    }

    @Test
    fun `listAppDraftListings returns app draft listings from only requested app draft`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId1 = createAppDraft(dataStore, appDraftApi, context)
            val appDraftId2 = createAppDraft(dataStore, appDraftApi, context)
            val appDraftListingId = createAppDraftListing(appDraftApi, context, appDraftId1)
            createAppDraftListing(appDraftApi, context, appDraftId2)

            val response = appDraftApi.listAppDraftListings(
                context,
                ListAppDraftListingsRequest(appDraftId1, 2u, None),
            )
                .map { it.appDraftListings }

            assertEquals(
                listOf(
                    ApiAppDraftListing(
                        id = appDraftListingId,
                        appDraftId = appDraftId1,
                        language = "en-US",
                        name = "Example App",
                        shortDescription = "Example Short Description",
                    )
                ).right(),
                response,
            )
        }
    }

    @Test
    fun `listAppDraftListings returns only authorized app draft listings`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val randomSource = DeterministicRandomSource()
            val context1 = signIn(dataStore, randomSource, ExternalUserId.Github(1))
            val context2 = signIn(dataStore, randomSource, ExternalUserId.Github(2))
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId1 = createAppDraft(dataStore, appDraftApi, context1)
            createAppDraftListing(appDraftApi, context1, appDraftId1)

            val response = appDraftApi.listAppDraftListings(
                context2,
                ListAppDraftListingsRequest(appDraftId1, 1u, None),
            )
                .map { it.appDraftListings }

            assertEquals(emptyList<ApiAppDraftListing>().right(), response)
        }
    }

    @Test
    fun `updateAppDraftListing returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val request = UpdateAppDraftListingRequest("appDraftListing1", None, None)
            val response = appDraftApi.updateAppDraftListing(context, request)

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `updateAppDraftListing returns AppDraftSubmitted if app draft is already submitted`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val request = UpdateAppDraftListingRequest("appDraftListing1", None, None)
            val response = appDraftApi.updateAppDraftListing(CallContext(Some("session1")), request)

            assertEquals(AppDraftSubmittedError("appDraft1"), response.unwrapErr())
        }
    }

    @Test
    fun `updateAppDraftListing updates all requested fields for authorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)
            val appDraftListingId = createAppDraftListing(appDraftApi, context, appDraftId)

            val request = UpdateAppDraftListingRequest(
                appDraftListingId = appDraftListingId,
                name = Some("App Name"),
                shortDescription = Some("App Short Description"),
            )
            appDraftApi.updateAppDraftListing(context, request).unwrap()
            val getResponse = appDraftApi
                .getAppDraftListing(context, GetAppDraftListingRequest(appDraftListingId))
                .unwrap()

            assertEquals(
                GetAppDraftListingResponse(
                    ApiAppDraftListing(
                        id = appDraftListingId,
                        appDraftId = appDraftId,
                        language = "en-US",
                        name = "App Name",
                        shortDescription = "App Short Description",
                    )
                ),
                getResponse,
            )
        }
    }

    @Test
    fun `uploadAppDraftListingIcon returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.uploadAppDraftListingIcon(
                context,
                UploadAppDraftListingIconRequest("appDraftListing1"),
            )

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `uploadAppDraftListingIcon returns AppDraftSubmitted when app draft is already submitted`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val response = appDraftApi.uploadAppDraftListingIcon(
                CallContext(Some("session1")),
                UploadAppDraftListingIconRequest("appDraftListing1"),
            )

            assertEquals(AppDraftSubmittedError("appDraft1"), response.unwrapErr())
        }
    }

    @Test
    fun `uploadAppDraftListingIcon returns successfully when permitted`() {
        val randomSource = DeterministicRandomSource()
        LocalBlobStorage(randomSource).use { blobStorage ->
            InMemoryDataStore(randomSource).use { dataStore ->
                dataStore.migrateToHead().unwrap()
                val context = signIn(dataStore)
                val appDraftApi = makeAppDraftApi(dataStore, blobStorage = blobStorage)
                val appDraftId = createAppDraft(dataStore, appDraftApi, context)
                val appDraftListingId = createAppDraftListing(appDraftApi, context, appDraftId)

                val response = appDraftApi.uploadAppDraftListingIcon(
                    context,
                    UploadAppDraftListingIconRequest(appDraftListingId),
                )

                assertEquals(
                    UploadAppDraftListingIconResponse(
                        uploadUri = HttpUri
                            .fromString(
                                "http://localhost:${blobStorage.port}/upload/" +
                                        "bb20b45f-d4d9-5138-3d93-cb799b3970be"
                            )
                            .unwrap(),
                    )
                        .right(),
                    response,
                )
            }
        }
    }

    @Test
    fun `downloadAppDraftListingIcon returns InsufficientPermission for unauthorized request`() {
        // TODO
    }

    @Test
    fun `downloadAppDraftListingIcon returns AppDraftListingIconNotFound when app draft listing exists without icon`() {
        // TODO
    }

    @Test
    fun `downloadAppDraftListingIcon returns successfully when permitted`() {
        // TODO
    }

    @Test
    fun `deleteAppDraftListing returns InsufficientPermission for unauthorized request`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)

            val request = DeleteAppDraftListingRequest("appDraftListing1")
            val response = appDraftApi.deleteAppDraftListing(context, request)

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `deleteAppDraftListing returns AppDraftSubmitted if app draft has been submitted`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.runTxWithRetry { tx ->
                signInNewUser(tx).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", FixedTimestampSource().now()).bind()
            }
                .unwrap2()
            val appDraftApi = makeAppDraftApi(dataStore)

            val request = DeleteAppDraftListingRequest("appDraftListing1")
            val response = appDraftApi.deleteAppDraftListing(CallContext(Some("session1")), request)

            assertEquals(AppDraftSubmittedError("appDraft1"), response.unwrapErr())
        }
    }

    @Test
    fun `deleteAppDraftListing unsets default listing if listing is the app draft default`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)
            val appDraftListingId = createAppDraftListing(appDraftApi, context, appDraftId)
            appDraftApi
                .updateAppDraft(context, UpdateAppDraftRequest(appDraftId, appDraftListingId))
                .unwrap()

            val request = DeleteAppDraftListingRequest(appDraftListingId)
            appDraftApi.deleteAppDraftListing(context, request).unwrap()
            val getResponse = appDraftApi
                .getAppDraft(context, GetAppDraftRequest(appDraftId))
                .unwrap()

            assertEquals(None, getResponse.appDraft.optionalDefaultAppDraftListingId)
        }
    }

    @Test
    fun `deleteAppDraftListing deletes app draft listing`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val appDraftApi = makeAppDraftApi(dataStore)
            val appDraftId = createAppDraft(dataStore, appDraftApi, context)
            val appDraftListingId = createAppDraftListing(appDraftApi, context, appDraftId)

            val request = DeleteAppDraftListingRequest(appDraftListingId)
            appDraftApi.deleteAppDraftListing(context, request).unwrap()
            val response = appDraftApi
                .getAppDraftListing(context, GetAppDraftListingRequest(appDraftListingId))

            assertEquals(InsufficientPermissionError, response.unwrapErr())
        }
    }

    @Test
    fun `publishAppDraft returns InsufficientPermission for unauthorized request`() {
        // TODO
    }

    @Test
    fun `publishAppDraft returns AppDraftAlreadyPublished if app draft is already published`() {
        // TODO
    }

    @Test
    fun `publishAppDraft returns AppDraftPublishing if app draft is currently publishing`() {
        // TODO
    }

    @Test
    fun `publishAppDraft returns AppWithSameIdAlreadyExists if a published app with the same ID already exists`() {
        // TODO
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
            val appDraftApi = makeAppDraftApi(
                dataStore,
                timestampSource = FixedTimestampSource(UNIX_EPOCH.plusDays(2)),
            )
            val context = when (testCase.session) {
                UnauthenticatedCallTestCase.Session.NONE -> CallContext(None)
                UnauthenticatedCallTestCase.Session.EXPIRED -> expiredContext
            }

            val response = testCase.call(appDraftApi, context)

            assertEquals(UnauthenticatedError, response.unwrapErr())
        }
    }

    private fun createAppDraft(
        dataStore: DataStore,
        appDraftApi: AppDraftApi,
        context: CallContext,
    ): String {
        return appDraftApi
            .createAppDraft(context, CreateAppDraftRequest(getMyOrganizationId(dataStore, context)))
            .unwrap()
            .appDraftId
    }

    private fun createAppDraftListing(
        appDraftApi: AppDraftApi,
        context: CallContext,
        appDraftId: String,
        name: String = "Example App",
        shortDescription: String = "Example Short Description",
    ): String {
        val request = CreateAppDraftListingRequest(
            appDraftId = appDraftId,
            language = ListingLanguage.EN_US,
            name = name,
            shortDescription = shortDescription,
        )

        return appDraftApi.createAppDraftListing(context, request).unwrap().appDraftListingId
    }

    private fun makeAppDraftApi(
        dataStore: DataStore,
        randomSource: RandomSource = DeterministicRandomSource(),
        blobStorage: LocalBlobStorage = LocalBlobStorage(randomSource),
        timestampSource: TimestampSource = FixedTimestampSource(),
        appDraftUploadBucketName: String = "app-draft-uploads",
        appDraftListingIconUploadBucketName: String = "app-draft-listing-icon-uploads",
    ): AppDraftApi {
        return AppDraftApiImpl(
            dataStore,
            LocalOnlyBlobStorage(blobStorage),
            randomSource,
            timestampSource,
            appDraftUploadBucketName,
            appDraftListingIconUploadBucketName,
        )
    }

    companion object {
        data class UnauthenticatedCallTestCase(
            val method: String,
            val session: Session,
            val call: (AppDraftApi, CallContext) -> Either<*, *>,
        ) {
            enum class Session { NONE, EXPIRED }

            override fun toString(): String = "$method, $session"
        }

        @JvmStatic
        private fun unauthenticatedCallTestCases(): List<UnauthenticatedCallTestCase> {
            val calls: List<Pair<String, (AppDraftApi, CallContext) -> Either<*, *>>> = listOf(
                "createAppDraft" to { api, context ->
                    api.createAppDraft(context, CreateAppDraftRequest("org1"))
                },
                "getAppDraft" to { api, context ->
                    api.getAppDraft(context, GetAppDraftRequest("appDraft1"))
                },
                "listAppDrafts" to { api, context ->
                    api.listAppDrafts(context, ListAppDraftsRequest("org1", 1u, None))
                },
                "uploadAppDraft" to { api, context ->
                    api.uploadAppDraft(context, UploadAppDraftRequest("appDraft1"))
                },
                "downloadAppDraft" to { api, context ->
                    api.downloadAppDraft(context, DownloadAppDraftRequest("appDraft1"))
                },
                "updateAppDraft" to { api, context ->
                    api.updateAppDraft(context, UpdateAppDraftRequest("appDraft1", "appDraftListing1"))
                },
                "submitAppDraft" to { api, context ->
                    api.submitAppDraft(context, SubmitAppDraftRequest("appDraft1"))
                },
                "deleteAppDraft" to { api, context ->
                    api.deleteAppDraft(context, DeleteAppDraftRequest("appDraft1"))
                },
                "createAppDraftListing" to { api, context ->
                    api.createAppDraftListing(
                        context,
                        CreateAppDraftListingRequest(
                            appDraftId = "appDraft1",
                            language = ListingLanguage.EN_US,
                            name = "App Name",
                            shortDescription = "App Short Description",
                        ),
                    )
                },
                "getAppDraftListing" to { api, context ->
                    api.getAppDraftListing(context, GetAppDraftListingRequest("appDraftListing1"))
                },
                "listAppDraftListings" to { api, context ->
                    api.listAppDraftListings(context, ListAppDraftListingsRequest("appDraft1", 1u, None))
                },
                "updateAppDraftListing" to { api, context ->
                    api.updateAppDraftListing(
                        context,
                        UpdateAppDraftListingRequest("appDraftListing1", None, None),
                    )
                },
                "uploadAppDraftListingIcon" to { api, context ->
                    api.uploadAppDraftListingIcon(
                        context,
                        UploadAppDraftListingIconRequest("appDraftListing1"),
                    )
                },
                "deleteAppDraftListing" to { api, context ->
                    api.deleteAppDraftListing(
                        context,
                        DeleteAppDraftListingRequest("appDraftListing1"),
                    )
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
