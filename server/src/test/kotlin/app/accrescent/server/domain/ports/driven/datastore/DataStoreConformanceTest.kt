// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

import app.accrescent.server.UNIX_EPOCH
import app.accrescent.server.appDraftListingApiView
import app.accrescent.server.appPackage
import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrap2
import app.accrescent.server.core.unwrapErr
import app.accrescent.server.createAppDraftListing
import app.accrescent.server.domain.android.AndroidManifest
import app.accrescent.server.domain.android.ApkParseError
import app.accrescent.server.domain.android.ApkSetParseError
import app.accrescent.server.domain.android.ApplicationId
import app.accrescent.server.domain.android.NameAttribute
import app.accrescent.server.domain.android.VersionName
import app.accrescent.server.domain.appstore.ListingLanguage
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.incompletePendingAppDraftListingIconUpload
import app.accrescent.server.incompletePendingAppDraftUpload
import app.accrescent.server.pendingExternalBlob
import app.accrescent.server.saveAppPackageFromNewUpload
import app.accrescent.server.unsubmittedAppDraftApiView
import arrow.core.None
import arrow.core.Some
import arrow.core.left
import arrow.core.raise.context.bind
import arrow.core.raise.context.either
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Conformance test suite for [DataStore] implementations.
 */
abstract class DataStoreConformanceTest {
    /**
     * Runs a lambda with a new [DataStore] instance.
     *
     * Each call creates a new data store instance which shares no state with any other instance.
     * Attempts to call any methods on the [DataStore] outside of [block]'s scope result in
     * undefined behavior.
     *
     * @param block the lambda to run with access to a new [DataStore] instance.
     * @return the return value of [block].
     */
    protected abstract fun <T> withDataStore(block: (DataStore) -> T): T

    /**
     * Convenience method for running a lambda with a new, migrated [DataStore] instance.
     *
     * This method has almost the same behavior as [withDataStore], but migrates the [DataStore] to
     * head before running [block]. If migrating fails, this method will throw.
     *
     * Implementations may override this method, e.g., to reach that state more cheaply than by
     * calling [DataStore.migrateToHead], but the data store's state must be identical to that
     * produced by [DataStore.migrateToHead].
     *
     * @param block the lambda to run with access to a new, migrated [DataStore] instance.
     * @return the return value of [block].
     * @throws Throwable if migrating fails.
     */
    protected open fun <T> withMigratedDataStore(block: (DataStore) -> T): T {
        return withDataStore { dataStore ->
            dataStore.migrateToHead().unwrap()
            block(dataStore)
        }
    }

    @Test
    fun `migrateToHead returns successfully if migrations are up-to-date`() {
        withDataStore { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.migrateToHead().unwrap()
        }
    }

    @Test
    fun `runTxWithRetry returns the value returned by block`() {
        withMigratedDataStore { dataStore ->

            val result = dataStore.runTxWithRetry<_, Nothing> { "result" }.unwrap2()

            assertEquals("result", result)
        }
    }

    @Test
    fun `runTxWithRetry propagates exception thrown by block`() {
        withMigratedDataStore { dataStore ->
            class CustomException : Exception()

            assertThrows<CustomException> {
                dataStore.runTxWithRetry<_, Nothing> { throw CustomException() }.unwrap2()
            }
        }
    }

    @Test
    fun `runTxWithRetry rolls back write when block throws`() {
        withMigratedDataStore { dataStore ->
            class CustomException : Exception()

            assertThrows<CustomException> {
                dataStore.runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    throw CustomException()
                }.unwrap2()
            }

            // Saving this data again can succeed only if the previous query rolled back since user
            // IDs and organization IDs must be unique
            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                }
                .unwrap()

            assertEquals(Unit.right(), result)
        }
    }

    @Test
    fun `runTxWithRetry rolls back write when block raises an error`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                raise(DataStoreError.IllegalState)
            }.unwrap()

            assertEquals(DataStoreError.IllegalState.left(), result)
            // Saving this data again can succeed only if the previous query rolled back since user
            // IDs and organization IDs must be unique
            val secondSaveResult = dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                }
                .unwrap()

            assertEquals(Unit.right(), secondSaveResult)
        }
    }

    @Test
    fun `runTxWithRetry commits write when block completes successfully`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }.unwrap2()

            // Saving this data again will error with a consistency violation only if the previous
            // query committed since user IDs and organization IDs must be unique
            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.ConsistencyViolation, error)
        }
    }

    @Test
    fun `appDrafts countActiveInOrganization returns accurate count`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.organizations.saveWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                tx.appDrafts.create("org2", "appDraft3", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft4", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft5", UNIX_EPOCH).bind()
            }.unwrap2()

            val count = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.countActiveInOrganization("org1").bind() }
                .unwrap2()

            assertEquals(4uL, count)
        }
    }

    @Test
    fun `appDrafts create returns ConsistencyViolationError for duplicate app draft ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "draft1", UNIX_EPOCH).bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.create("org1", "draft1", UNIX_EPOCH).bind() }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts create returns ConsistencyViolationError when organization does not exist`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.create("org1", "draft1", UNIX_EPOCH).bind() }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts createListing returns ConsistencyViolationError for duplicate ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }
                .unwrap2()

            val result = dataStore
                .runTxWithRetry { tx -> createAppDraftListing(tx).bind() }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts createListing returns ConsistencyViolationError for duplicate (appDraftId, language) pair`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx, "appDraftListing1", "appDraft1", ListingLanguage.EN_US).bind()
            }
                .unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    createAppDraftListing(tx, "appDraftListing2", "appDraft1", ListingLanguage.EN_US)
                        .bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts createListing returns ConsistencyViolationError when app draft does not exist`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx -> createAppDraftListing(tx).bind() }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts deleteById returns EntityNotFound for non-existent app draft`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.deleteById("appDraft1", UNIX_EPOCH).bind() }
                .unwrap()

            assertEquals(DataStoreError.EntityNotFound, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts deleteById makes findApiViewById return None`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx -> tx.appDrafts.deleteById("appDraft1", UNIX_EPOCH).bind() }
                .unwrap2()
            val appDraftApiView = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.findApiViewById("appDraft1").bind() }
                .unwrap2()

            assertTrue(appDraftApiView.isNone())
        }
    }

    @Test
    fun `appDrafts deleteById deletes listings for app draft`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx -> tx.appDrafts.deleteById("appDraft1", UNIX_EPOCH).bind() }
                .unwrap2()
            val listingExists = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts
                        .listingExistsByIdForAppDraft("appDraftListing1", "appDraft1")
                        .bind()
                }
                .unwrap2()

            assertFalse(listingExists)
        }
    }

    @Test
    fun `appDrafts deleteById deletes pending uploads for app draft`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob()).bind()
            }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx -> tx.appDrafts.deleteById("appDraft1", UNIX_EPOCH).bind() }
                .unwrap2()
            val pendingUpload = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.findPendingUploadByObjectKey("object1").bind()
                }
                .unwrap2()

            assertTrue(pendingUpload.isNone())
        }
    }

    @Test
    fun `appDrafts deleteListingById returns EntityNotFound for non-existent app draft listing`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.deleteListingById("appDraftListing1", UNIX_EPOCH).bind() }
                .unwrap()

            assertEquals(DataStoreError.EntityNotFound, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts deleteListingById makes findListingApiViewById return None`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx -> tx.appDrafts.deleteListingById("appDraftListing1", UNIX_EPOCH).bind() }
                .unwrap2()
            val listing = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.findListingApiViewById("appDraftListing1").bind() }
                .unwrap2()

            assertTrue(listing.isNone())
        }
    }

    @Test
    fun `appDrafts deletePendingListingIconUploadByListingId returns EntityNotFound when no pending icon upload exists`() {
        withMigratedDataStore { dataStore ->
            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.deletePendingListingIconUploadByListingId("appDraftListing1", UNIX_EPOCH).bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `appDrafts deletePendingListingIconUploadByListingId makes findPendingListingIconUploadByObjectKey return None`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.saveListingIconUpload(
                    incompletePendingAppDraftListingIconUpload(),
                    pendingExternalBlob(),
                )
                    .bind()
            }.unwrap2()

            dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.deletePendingListingIconUploadByListingId("appDraftListing1", UNIX_EPOCH).bind()
                }
                .unwrap2()
            val foundUpload = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.findPendingListingIconUploadByObjectKey("object1").bind()
                }
                .unwrap2()

            assertTrue(foundUpload.isNone())
        }
    }

    @Test
    fun `appDrafts deletePendingUploadByAppDraftId returns EntityNotFound when no pending upload exists`() {
        withMigratedDataStore { dataStore ->
            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.deletePendingUploadByAppDraftId("appDraft1", UNIX_EPOCH).bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `appDrafts deletePendingUploadByAppDraftId makes findPendingUploadByObjectKey return None`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob()).bind()
            }.unwrap2()

            dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.deletePendingUploadByAppDraftId("appDraft1", UNIX_EPOCH).bind()
                }
                .unwrap2()
            val foundUpload = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.findPendingUploadByObjectKey("object1").bind()
                }
                .unwrap2()

            assertTrue(foundUpload.isNone())
        }
    }

    @Test
    fun `appDrafts existsSubmittedForAppId returns false when no app draft exists for given app ID`() {
        withMigratedDataStore { dataStore ->
            val appId = ApplicationId.fromString("com.example.app").unwrap()

            val exists = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.existsSubmittedForAppId(appId).bind() }
                .unwrap2()

            assertFalse(exists)
        }
    }

    @Test
    fun `appDrafts existsSubmittedForAppId returns true when submitted app draft exists for given app ID`() {
        withMigratedDataStore { dataStore ->
            val originalAppPackage = appPackage()

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx, originalAppPackage).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()

            val exists = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.existsSubmittedForAppId(originalAppPackage.appId).bind()
                }
                .unwrap2()

            assertTrue(exists)
        }
    }

    @Test
    fun `appDrafts findApiViewById returns None when no app draft with the given ID exists`() {
        withMigratedDataStore { dataStore ->
            val foundAppDraft = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.findApiViewById("appDraft1").bind() }
                .unwrap2()

            assertTrue(foundAppDraft.isNone())
        }
    }

    @Test
    fun `appDrafts findApiViewById returns an unsubmitted view for an unsubmitted app draft`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
            }.unwrap2()

            val foundAppDraft = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.findApiViewById("appDraft1").bind() }
                .unwrap2()

            assertEquals(
                Some(
                    AppDraftApiView.Unsubmitted(
                        id = "appDraft1",
                        createTime = UNIX_EPOCH,
                        defaultAppDraftListingId = None,
                        appPackage = None,
                    )
                ),
                foundAppDraft,
            )
        }
    }

    @Test
    fun `appDrafts findApiViewById returns a submitted view for a submitted app draft`() {
        withMigratedDataStore { dataStore ->
            val originalAppPackage = appPackage()

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx, originalAppPackage).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }.unwrap2()

            val foundAppDraft = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.findApiViewById("appDraft1").bind() }
                .unwrap2()

            assertEquals(
                Some(
                    AppDraftApiView.Submitted(
                        id = "appDraft1",
                        createTime = UNIX_EPOCH,
                        defaultAppDraftListingId = "appDraftListing1",
                        appPackage = AppPackageApiView(
                            androidApplicationId = originalAppPackage.appId,
                            versionCode = originalAppPackage.versionCode,
                            versionName = originalAppPackage.versionName,
                            targetSdk = originalAppPackage.targetSdk,
                        ),
                        submitTime = UNIX_EPOCH,
                    )
                ),
                foundAppDraft,
            )
        }
    }

    @Test
    fun `appDrafts findApiViewsForOrganizationAndUserByQuery returns app draft API views from only requested organization`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.organizations.saveWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org2", "appDraft2", UNIX_EPOCH).bind()
            }.unwrap2()

            val appDrafts = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts
                        .findApiViewsForOrganizationAndUserByQuery(
                            "org1",
                            "user1",
                            NonNegativeInt.new(2).unwrap(),
                            None,
                        )
                        .bind()
                }
                .unwrap2()

            assertEquals(listOf(unsubmittedAppDraftApiView(id = "appDraft1")), appDrafts)
        }
    }

    @Test
    fun `appDrafts findApiViewsForOrganizationAndUserByQuery returns only authorized app draft API views`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.organizations.saveWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
            }.unwrap2()

            val appDrafts = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts
                        .findApiViewsForOrganizationAndUserByQuery(
                            "org1",
                            "user2",
                            NonNegativeInt.new(1).unwrap(),
                            None,
                        )
                        .bind()
                }
                .unwrap2()

            assertTrue(appDrafts.isEmpty())
        }
    }

    @Test
    fun `appDrafts findApiViewsForOrganizationAndUserByQuery respects maxResults`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
            }.unwrap2()

            val appDrafts = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts
                        .findApiViewsForOrganizationAndUserByQuery(
                            "org1",
                            "user1",
                            NonNegativeInt.new(1).unwrap(),
                            None,
                        )
                        .bind()
                }
                .unwrap2()

            assertEquals(1, appDrafts.size)
        }
    }

    @Test
    fun `appDrafts findApiViewsForOrganizationAndUserByQuery returns only items after afterAppDraftId`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
            }.unwrap2()

            val appDrafts = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts
                        .findApiViewsForOrganizationAndUserByQuery(
                            "org1",
                            "user1",
                            NonNegativeInt.new(2).unwrap(),
                            Some("appDraft1"),
                        )
                        .bind()
                }
                .unwrap2()

            assertEquals(listOf(unsubmittedAppDraftApiView(id = "appDraft2")), appDrafts)
        }
    }

    @Test
    fun `appDrafts findListingApiViewById returns None when no listing with the given ID exists`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.findListingApiViewById("appDraftListing1").bind() }
                .unwrap2()

            assertTrue(result.isNone())
        }
    }

    @Test
    fun `appDrafts findPendingListingIconUploadByObjectKey returns None when no upload for the given object exists`() {
        withMigratedDataStore { dataStore ->
            val foundUpload = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.findPendingListingIconUploadByObjectKey("object1").bind()
                }
                .unwrap2()

            assertTrue(foundUpload.isNone())
        }
    }

    @Test
    fun `appDrafts findPendingUploadByObjectKey returns None when no upload for the given object exists`() {
        withMigratedDataStore { dataStore ->
            val foundUpload = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.findPendingUploadByObjectKey("object1").bind()
                }
                .unwrap2()

            assertTrue(foundUpload.isNone())
        }
    }

    @Test
    fun `appDrafts findListingApiViewsForAppDraftAndUserByQuery returns listings for only requested app draft`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                createAppDraftListing(tx, "appDraftListing1", "appDraft1").bind()
                createAppDraftListing(tx, "appDraftListing2", "appDraft2").bind()
            }.unwrap2()

            val listings = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts
                        .findListingApiViewsForAppDraftAndUserByQuery(
                            appDraftId = "appDraft1",
                            userId = "user1",
                            maxResults = NonNegativeInt.new(2).unwrap(),
                            afterLanguage = None,
                        )
                        .bind()
                }
                .unwrap2()

            assertEquals(
                listOf(appDraftListingApiView(id = "appDraftListing1", appDraftId = "appDraft1")),
                listings,
            )
        }
    }

    @Test
    fun `appDrafts findListingApiViewsForAppDraftAndUserByQuery returns only authorized listings`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
                tx.organizations.saveWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
            }.unwrap2()

            val listings = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts
                        .findListingApiViewsForAppDraftAndUserByQuery(
                            appDraftId = "appDraft1",
                            userId = "user2",
                            maxResults = NonNegativeInt.new(1).unwrap(),
                            afterLanguage = None,
                        )
                        .bind()
                }
                .unwrap2()

            assertEquals(emptyList<AppDraftListingApiView>(), listings)
        }
    }

    @Test
    fun `appDrafts hasDefaultListing returns EntityNotFound when no app draft with the given ID exists`() {
        withMigratedDataStore { dataStore ->
            val error = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.hasDefaultListing("appDraft1").bind() }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `appDrafts hasDefaultListing returns false when app draft has no default listing`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
            }.unwrap2()

            val hasDefaultListing = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.hasDefaultListing("appDraft1").bind() }
                .unwrap2()

            assertFalse(hasDefaultListing)
        }
    }

    @Test
    fun `appDrafts hasDefaultListing returns true when app draft has a default listing`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }.unwrap2()

            val hasDefaultListing = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.hasDefaultListing("appDraft1").bind() }
                .unwrap2()

            assertTrue(hasDefaultListing)
        }
    }

    @Test
    fun `appDrafts isSubmitted returns EntityNotFound when no app draft with the given ID exists`() {
        withMigratedDataStore { dataStore ->
            val error = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.isSubmitted("appDraft1").bind() }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `appDrafts isSubmitted returns false when app draft is not submitted`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
            }.unwrap2()

            val isSubmitted = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.isSubmitted("appDraft1").bind() }
                .unwrap2()

            assertFalse(isSubmitted)
        }
    }

    @Test
    fun `appDrafts isSubmitted returns true when app draft is submitted`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx, appPackage()).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }.unwrap2()

            val isSubmitted = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.isSubmitted("appDraft1").bind() }
                .unwrap2()

            assertTrue(isSubmitted)
        }
    }

    @Test
    fun `appDrafts listingExistsByIdForAppDraft returns false when no listing with the given ID exists`() {
        withMigratedDataStore { dataStore ->
            val exists = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.listingExistsByIdForAppDraft("appDraftListing1", "appDraft1").bind()
                }
                .unwrap2()

            assertFalse(exists)
        }
    }

    @Test
    fun `appDrafts listingExistsByIdForAppDraft returns false when listing exists with ID for different app draft`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                createAppDraftListing(tx, "appDraftListing1", "appDraft1").bind()
            }.unwrap2()

            val exists = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.listingExistsByIdForAppDraft("appDraftListing1", "appDraft2").bind()
                }
                .unwrap2()

            assertFalse(exists)
        }
    }

    @Test
    fun `appDrafts listingExistsByIdForAppDraft returns true when listing exists with given ID and app draft ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }.unwrap2()

            val exists = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.listingExistsByIdForAppDraft("appDraftListing1", "appDraft1").bind()
                }
                .unwrap2()

            assertTrue(exists)
        }
    }

    @Test
    fun `appDraft listingExistsByIdForAppDraft returns false when listing exists with app draft ID but not listing ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }.unwrap2()

            val exists = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.listingExistsByIdForAppDraft("appDraftListing2", "appDraft1").bind()
                }
                .unwrap2()

            assertFalse(exists)
        }
    }

    @Test
    fun `appDraft listingExistsByLanguageForAppDraft returns false when no listing exists`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore.runTxWithRetry { tx ->
                tx.appDrafts
                    .listingExistsByLanguageForAppDraft("appDraft1", ListingLanguage.EN_US)
                    .bind()
            }
                .unwrap2()

            assertFalse(result)
        }
    }

    @Test
    fun `appDraft listingExistsByLanguageForAppDraft returns true when listing exists with app draft ID and language`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }
                .unwrap2()

            val result = dataStore.runTxWithRetry { tx ->
                tx.appDrafts
                    .listingExistsByLanguageForAppDraft("appDraft1", ListingLanguage.EN_US)
                    .bind()
            }
                .unwrap2()

            assertTrue(result)
        }
    }

    @Test
    fun `appDrafts listingIsDefault returns false when no listing with the given ID exists`() {
        withMigratedDataStore { dataStore ->
            val isDefault = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.listingIsDefault("appDraftListing1").bind() }
                .unwrap2()

            assertFalse(isDefault)
        }
    }

    @Test
    fun `appDrafts listingIsDefault returns false when listing is not its app draft default listing`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }.unwrap2()

            val isDefault = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.listingIsDefault("appDraftListing1").bind() }
                .unwrap2()

            assertFalse(isDefault)
        }
    }

    @Test
    fun `appDrafts listingIsDefault returns true when listing is its app draft default listing`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }.unwrap2()

            val isDefault = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.listingIsDefault("appDraftListing1").bind() }
                .unwrap2()

            assertTrue(isDefault)
        }
    }

    @Test
    fun `appDrafts pendingListingIconUploadExistsByListingId returns false when no pending icon upload exists`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.pendingListingIconUploadExistsByListingId("appDraftListing1").bind()
                }
                .unwrap2()

            assertFalse(result)
        }
    }

    @Test
    fun `appDrafts pendingListingIconUploadExistsByListingId returns true when pending icon upload exists`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.saveListingIconUpload(
                    incompletePendingAppDraftListingIconUpload(),
                    pendingExternalBlob(),
                )
                    .bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.pendingListingIconUploadExistsByListingId("appDraftListing1").bind()
                }
                .unwrap2()

            assertTrue(result)
        }
    }

    @Test
    fun `appDrafts pendingUploadExistsByAppDraftId returns false when no pending upload exists`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.pendingUploadExistsByAppDraftId("appDraft1").bind() }
                .unwrap2()

            assertFalse(result)
        }
    }

    @Test
    fun `appDrafts pendingUploadExistsByAppDraftId returns true when pending upload exists`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob()).bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.pendingUploadExistsByAppDraftId("appDraft1").bind() }
                .unwrap2()

            assertTrue(result)
        }
    }

    @Test
    fun `appDrafts saveListingIconUpload returns ConsistencyViolationError for duplicate ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                createAppDraftListing(tx, "appDraftListing1", "appDraft1").bind()
                createAppDraftListing(tx, "appDraftListing2", "appDraft2").bind()
                tx.appDrafts.saveListingIconUpload(
                    incompletePendingAppDraftListingIconUpload(
                        appDraftListingId = "appDraftListing1",
                        objectKey = "object1",
                    ),
                    pendingExternalBlob(),
                ).bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.saveListingIconUpload(
                        incompletePendingAppDraftListingIconUpload(
                            appDraftListingId = "appDraftListing2",
                            externalBlobId = "blob2",
                            objectKey = "object2",
                        ),
                        pendingExternalBlob(id = "blob2", objectKey = "object2"),
                    ).bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts saveListingIconUpload returns ConsistencyViolationError for duplicate app draft listing ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.saveListingIconUpload(
                    incompletePendingAppDraftListingIconUpload(),
                    pendingExternalBlob(),
                )
                    .bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.saveListingIconUpload(
                        incompletePendingAppDraftListingIconUpload(
                            id = "adliu2",
                            externalBlobId = "blob2",
                            objectKey = "object2",
                        ),
                        pendingExternalBlob(id = "blob2", objectKey = "object2"),
                    ).bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts saveListingIconUpload returns ConsistencyViolationError for duplicate object key`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                createAppDraftListing(tx, "appDraftListing1", "appDraft1").bind()
                createAppDraftListing(tx, "appDraftListing2", "appDraft2").bind()
                tx.appDrafts
                    .saveListingIconUpload(
                        incompletePendingAppDraftListingIconUpload(objectKey = "object1"),
                        pendingExternalBlob(),
                    )
                    .bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.saveListingIconUpload(
                        incompletePendingAppDraftListingIconUpload(
                            id = "adliu2",
                            appDraftListingId = "appDraftListing2",
                            externalBlobId = "blob2",
                            objectKey = "object1",
                        ),
                        pendingExternalBlob(id = "blob2", bucketName = "bucket2"),
                    ).bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts saveListingIconUpload returns ConsistencyViolationError when app draft listing does not exist`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.saveListingIconUpload(
                        incompletePendingAppDraftListingIconUpload(),
                        pendingExternalBlob(),
                    )
                        .bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts saveListingIconUpload and findPendingListingIconUploadByObjectKey round-trip data`() {
        withMigratedDataStore { dataStore ->
            val originalUpload = incompletePendingAppDraftListingIconUpload()

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.saveListingIconUpload(originalUpload, pendingExternalBlob()).bind()
            }.unwrap2()
            val foundUpload = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.findPendingListingIconUploadByObjectKey("object1").bind()
                }
                .unwrap2()

            assertEquals(Some(originalUpload), foundUpload)
        }
    }

    @Test
    fun `appDrafts saveUpload returns ConsistencyViolationError for duplicate ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                tx.appDrafts
                    .saveUpload(
                        incompletePendingAppDraftUpload(
                            appDraftId = "appDraft1",
                            objectKey = "object1",
                        ),
                        pendingExternalBlob(),
                    )
                    .bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.saveUpload(
                        incompletePendingAppDraftUpload(
                            appDraftId = "appDraft2",
                            externalBlobId = "blob2",
                            objectKey = "object2",
                        ),
                        pendingExternalBlob(id = "blob2", objectKey = "object2"),
                    ).bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts saveUpload returns ConsistencyViolationError for duplicate app draft ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.saveUpload(
                    incompletePendingAppDraftUpload(appDraftId = "appDraft1"),
                    pendingExternalBlob(),
                )
                    .bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.saveUpload(
                        incompletePendingAppDraftUpload(
                            id = "appDraftUpload2",
                            appDraftId = "appDraft1",
                            externalBlobId = "blob2",
                            objectKey = "object2",
                        ),
                        pendingExternalBlob(id = "blob2", objectKey = "object2"),
                    ).bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts saveUpload returns ConsistencyViolationError for duplicate object key`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                tx.appDrafts
                    .saveUpload(
                        incompletePendingAppDraftUpload(objectKey = "object1"),
                        pendingExternalBlob(),
                    )
                    .bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.saveUpload(
                        incompletePendingAppDraftUpload(
                            id = "appDraftUpload2",
                            appDraftId = "appDraft2",
                            externalBlobId = "blob2",
                            objectKey = "object1",
                        ),
                        pendingExternalBlob(id = "blob2", bucketName = "bucket2"),
                    ).bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts saveUpload returns ConsistencyViolationError when app draft does not exist`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob()).bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts saveUpload and findPendingUploadByObjectKey round-trip data`() {
        withMigratedDataStore { dataStore ->
            val originalUpload = incompletePendingAppDraftUpload()

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.saveUpload(originalUpload, pendingExternalBlob()).bind()
            }.unwrap2()
            val foundUpload = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.findPendingUploadByObjectKey("object1").bind()
                }
                .unwrap2()

            assertEquals(Some(originalUpload), foundUpload)
        }
    }

    @Test
    fun `appDrafts updateDefaultListing returns ConsistencyViolationError if listing does not exist`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()

            val result = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts updateDefaultListing returns ConsistencyViolationError if listing belongs to different app draft`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }
                .unwrap2()

            val result = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateDefaultListing("appDraft2", Some("appDraftListing1")).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts updateDefaultListing returns EntityNotFound for non-existent app draft`() {
        withMigratedDataStore { dataStore ->

            val result = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.EntityNotFound, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts updateDefaultListing updates defaultAppDraftListingId for existing app draft`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }
                .unwrap2()

            dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }
                .unwrap2()
            val appDraftApiView = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.requireApiViewById("appDraft1").bind() }
                .unwrap2()

            assertEquals(
                Some("appDraftListing1"),
                (appDraftApiView as AppDraftApiView.Unsubmitted).defaultAppDraftListingId,
            )
        }
    }

    @Test
    fun `appDrafts updateDefaultListing unsets defaultAppDraftListingId when given None`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }
                .unwrap2()

            dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateDefaultListing("appDraft1", None).bind()
            }
                .unwrap2()
            val appDraftApiView = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.requireApiViewById("appDraft1").bind() }
                .unwrap2()

            assertEquals(
                None,
                (appDraftApiView as AppDraftApiView.Unsubmitted).defaultAppDraftListingId,
            )
        }
    }

    @Test
    fun `appDrafts updateListing returns EntityNotFound for non-existent app draft listing`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateListing("appDraftListing1", None, None).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.EntityNotFound, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts updateListing leaves null fields unchanged`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }
                .unwrap2()

            dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateListing("appDraftListing1", None, None).bind()
            }
                .unwrap2()
            val foundListing = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.findListingApiViewById("appDraftListing1").bind() }
                .unwrap2()

            assertEquals(Some(appDraftListingApiView()), foundListing)
        }
    }

    @Test
    fun `appDrafts updateListing updates non-null fields`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
            }
                .unwrap2()

            dataStore.runTxWithRetry { tx ->
                tx.appDrafts
                    .updateListing(
                        listingId = "appDraftListing1",
                        name = Some("Updated App Name"),
                        shortDescription = Some("Updated App Description"),
                    )
                    .bind()
            }
                .unwrap2()
            val foundListing = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.findListingApiViewById("appDraftListing1").bind() }
                .unwrap2()
                .unwrap()

            assertEquals("Updated App Name", foundListing.name)
            assertEquals("Updated App Description", foundListing.shortDescription)
        }
    }

    @Test
    fun `appDrafts completePendingUpload returns EntityNotFound for non-existent upload`() {
        withMigratedDataStore { dataStore ->
            val error = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.completePendingUpload(
                    "upload1",
                    AppDraftUploadProcessingError.AppDraftSubmitted,
                    UNIX_EPOCH,
                ).bind()
            }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `appDrafts completePendingUpload records the result for an incomplete upload`() {
        withMigratedDataStore { dataStore ->
            val originalUpload = incompletePendingAppDraftUpload()
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts.saveUpload(originalUpload, pendingExternalBlob()).bind()
                }
                .unwrap2()

            dataStore.runTxWithRetry { tx ->
                tx.appDrafts.completePendingUpload(
                    "appDraftUpload1",
                    AppDraftUploadProcessingError.AppDraftSubmitted,
                    UNIX_EPOCH,
                ).bind()
            }
                .unwrap2()
            val foundUpload = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.findPendingUploadByObjectKey("object1").bind()
            }
                .unwrap2()
                .unwrap()

            assertEquals(
                Some(AppDraftUploadProcessingError.AppDraftSubmitted.left()),
                foundUpload.optionalResult,
            )
        }
    }

    @ParameterizedTest
    @MethodSource("appDraftUploadProcessingErrors")
    fun `appDrafts completePendingUpload and findPendingUploadByObjectKey round-trip processing result`(
        error: AppDraftUploadProcessingError,
    ) {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob()).bind()
            }.unwrap2()

            dataStore.runTxWithRetry { tx ->
                tx.appDrafts.completePendingUpload("appDraftUpload1", error, UNIX_EPOCH).bind()
            }.unwrap2()
            val foundUpload = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.findPendingUploadByObjectKey("object1").bind()
            }.unwrap2()
                .unwrap()

            assertEquals(Some(error.left()), foundUpload.optionalResult)
        }
    }

    @Test
    fun `appDrafts updateSubmitTime returns EntityNotFound for non-existent app draft`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.EntityNotFound, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts updateSubmitTime updates submitTime for existing app draft`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }
                .unwrap2()

            dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap2()
            val appDraftApiView = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.requireApiViewById("appDraft1").bind() }
                .unwrap2()

            assertInstanceOf<AppDraftApiView.Submitted>(appDraftApiView)
            assertEquals(UNIX_EPOCH, appDraftApiView.submitTime)
        }
    }

    @Test
    fun `appDrafts updateSubmitTime returns ConsistencyViolationError if app draft does not have package`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                createAppDraftListing(tx).bind()
                tx.appDrafts.updateDefaultListing("appDraft1", Some("appDraftListing1")).bind()
            }
                .unwrap2()

            val result = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appDrafts updateSubmitTime returns ConsistencyViolationError if app draft does not have default listing`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
            }
                .unwrap2()

            val result = dataStore.runTxWithRetry { tx ->
                tx.appDrafts.updateSubmitTime("appDraft1", UNIX_EPOCH).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appPackages findAppIdByAppDraftId returns None when no package exists for app draft`() {
        withMigratedDataStore { dataStore ->
            val appId = dataStore
                .runTxWithRetry { tx -> tx.appPackages.findAppIdByAppDraftId("appDraft1").bind() }
                .unwrap2()

            assertTrue(appId.isNone())
        }
    }

    @Test
    fun `appPackages findAppIdByAppDraftId returns app ID of the app draft's package`() {
        withMigratedDataStore { dataStore ->
            val savedAppPackage = appPackage()

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx, savedAppPackage).bind()
            }
                .unwrap2()

            val appId = dataStore
                .runTxWithRetry { tx -> tx.appPackages.findAppIdByAppDraftId("appDraft1").bind() }
                .unwrap2()
                .unwrap()

            assertEquals(savedAppPackage.appId, appId)
        }
    }

    @Test
    fun `appPackages findByAppDraftId returns None when no package exists for app draft`() {
        withMigratedDataStore { dataStore ->
            val appPackage = dataStore
                .runTxWithRetry { tx -> tx.appPackages.findByAppDraftId("appDraft1").bind() }
                .unwrap2()

            assertTrue(appPackage.isNone())
        }
    }

    @Test
    fun `appPackages saveFromPendingUpload returns ConsistencyViolation for duplicate ID`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(tx).bind()
                }
                .unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    saveAppPackageFromNewUpload(
                        tx,
                        appPackage = appPackage(appDraftId = "appDraft2", externalBlobId = "blob2"),
                        pendingUploadId = "appDraftUpload2",
                        objectKey = "object2",
                    )
                        .bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `appPackages saveFromPendingUpload returns ConsistencyViolationError for non-existent upload`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                }
                .unwrap2()

            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.appPackages.saveFromPendingUpload(
                        pendingUploadId = "appDraftUpload1",
                        appPackage = appPackage(),
                        permissions = emptyMap(),
                        blobVersion = ExternalBlob.LocalBlobVersion(1),
                        replacedBlobDeleteTime = UNIX_EPOCH,
                    )
                        .bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.ConsistencyViolation, error)
        }
    }

    @Test
    fun `appPackages saveFromPendingUpload returns ConsistencyViolationError for an already completed upload`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob())
                        .bind()
                    tx.appDrafts
                        .completePendingUpload(
                            "appDraftUpload1",
                            AppDraftUploadProcessingError.AppDraftSubmitted,
                            UNIX_EPOCH,
                        )
                        .bind()
                }
                .unwrap2()

            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.appPackages.saveFromPendingUpload(
                        pendingUploadId = "appDraftUpload1",
                        appPackage = appPackage(),
                        permissions = emptyMap(),
                        blobVersion = ExternalBlob.LocalBlobVersion(1),
                        replacedBlobDeleteTime = UNIX_EPOCH,
                    )
                        .bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.ConsistencyViolation, error)
        }
    }

    @Test
    fun `appPackages saveFromPendingUpload points the app draft at the new package`() {
        withMigratedDataStore { dataStore ->
            val savedAppPackage = appPackage()

            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(tx, savedAppPackage).bind()
                }
                .unwrap2()

            val appDraftApiView = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.requireApiViewById("appDraft1").bind() }
                .unwrap2()

            assertEquals(
                Some(
                    AppPackageApiView(
                        androidApplicationId = savedAppPackage.appId,
                        versionCode = savedAppPackage.versionCode,
                        versionName = savedAppPackage.versionName,
                        targetSdk = savedAppPackage.targetSdk,
                    )
                ),
                (appDraftApiView as AppDraftApiView.Unsubmitted).appPackage,
            )
        }
    }

    @Test
    fun `appPackages saveFromPendingUpload commits the blob owned by the upload`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(tx).bind()
                }
                .unwrap2()

            val blob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap2()

            assertEquals(
                ExternalBlob.Status.Committed(ExternalBlob.LocalBlobVersion(1)),
                blob.status,
            )
        }
    }

    @Test
    fun `appPackages saveFromPendingUpload completes the upload it commits`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(tx).bind()
                }
                .unwrap2()

            val upload = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.findPendingUploadByObjectKey("object1").bind()
                }
                .unwrap2()
                .unwrap()

            assertEquals(Some(Unit.right()), upload.optionalResult)
        }
    }

    @Test
    fun `appPackages saveFromPendingUpload and findByAppDraftId round-trip data`() {
        withMigratedDataStore { dataStore ->
            val originalAppPackage = appPackage()

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx, originalAppPackage).bind()
            }
                .unwrap2()
            val foundAppPackage = dataStore
                .runTxWithRetry { tx -> tx.appPackages.findByAppDraftId("appDraft1").bind() }
                .unwrap2()

            assertEquals(Some(originalAppPackage), foundAppPackage)
        }
    }

    @Test
    fun `apps countInAppDraftOrganization returns count of only apps in app draft's organization`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.organizations.saveWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.apps.saveWithDefaultListing(
                    App("app1", "org1", "appListing1", false),
                    AppListing("appListing1", "app1", ListingLanguage.EN_US),
                ).bind()
                tx.apps.saveWithDefaultListing(
                    App("app2", "org1", "appListing2", false),
                    AppListing("appListing2", "app2", ListingLanguage.EN_US),
                ).bind()
                tx.apps.saveWithDefaultListing(
                    App("app3", "org2", "appListing3", false),
                    AppListing("appListing3", "app3", ListingLanguage.EN_US),
                ).bind()
            }.unwrap2()

            val count = dataStore
                .runTxWithRetry { tx -> tx.apps.countInAppDraftOrganization("appDraft1").bind() }
                .unwrap2()

            assertEquals(2uL, count)
        }
    }

    @Test
    fun `apps countInAppDraftOrganization returns zero for nonexistent app draft`() {
        withMigratedDataStore { dataStore ->
            val count = dataStore
                .runTxWithRetry { tx -> tx.apps.countInAppDraftOrganization("appDraft1").bind() }
                .unwrap2()

            assertEquals(0uL, count)
        }
    }

    @Test
    fun `apps findById returns None when no app with the given ID exists`() {
        withMigratedDataStore { dataStore ->

            val foundApp = dataStore
                .runTxWithRetry { tx -> tx.apps.findById("app1").bind() }
                .unwrap2()

            assertTrue(foundApp.isNone())
        }
    }

    @Test
    fun `apps saveWithDefaultListing and findById round-trip data`() {
        withMigratedDataStore { dataStore ->
            val originalApp = App(
                id = "app1",
                organizationId = "org1",
                defaultAppListingId = "appListing1",
                publiclyListed = false,
            )

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.apps.saveWithDefaultListing(
                    originalApp,
                    AppListing("appListing1", "app1", ListingLanguage.EN_US),
                ).bind()
            }.unwrap2()
            val foundApp = dataStore
                .runTxWithRetry { tx -> tx.apps.findById(originalApp.id).bind() }
                .unwrap2()
            dataStore
                .runTxWithRetry { tx -> tx.apps.updatePubliclyListed("app1", true).bind() }
                .unwrap2()

            assertEquals(Some(originalApp), foundApp)
        }
    }

    @Test
    fun `apps saveWithDefaultListing returns ConsistencyViolationError for duplicate app ID`() {
        withMigratedDataStore { dataStore ->
            val app = App("app1", "org1", "appListing1", false)
            val listing = AppListing("appListing1", "app1", ListingLanguage.EN_US)
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.apps.saveWithDefaultListing(app, listing).bind()
            }.unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.apps.saveWithDefaultListing(
                        app.copy(defaultAppListingId = "appListing2"),
                        listing.copy(id = "appListing2"),
                    ).bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `apps saveWithDefaultListing returns ConsistencyViolationError when organization does not exist`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.apps.saveWithDefaultListing(
                        App("app1", "nonexistent-org", "appListing1", false),
                        AppListing("appListing1", "app1", ListingLanguage.EN_US),
                    ).bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `apps saveWithDefaultListing returns ConsistencyViolationError when app default listing ID does not match listing ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.apps.saveWithDefaultListing(
                    App("app1", "org1", "appListing1", false),
                    AppListing("appListing1", "app1", ListingLanguage.EN_US),
                ).bind()
            }.unwrap2()

            val result = dataStore.runTxWithRetry { tx ->
                tx.apps.saveWithDefaultListing(
                    App("app2", "org1", "appListing1", false),
                    AppListing("appListing2", "app2", ListingLanguage.EN_US),
                ).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `apps saveWithDefaultListing returns ConsistencyViolationError when listing app ID does not match app ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.apps.saveWithDefaultListing(
                    App("app2", "org1", "appListing2", false),
                    AppListing("appListing2", "app2", ListingLanguage.EN_US),
                ).bind()
            }.unwrap2()

            val result = dataStore.runTxWithRetry { tx ->
                tx.apps.saveWithDefaultListing(
                    App("app1", "org1", "appListing1", false),
                    AppListing("appListing1", "app2", ListingLanguage.EN_US),
                ).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `apps updatePubliclyListed returns EntityNotFound for non-existent app`() {
        withMigratedDataStore { dataStore ->

            val result = dataStore.runTxWithRetry { tx ->
                tx.apps.updatePubliclyListed("app1", true).bind()
            }
                .unwrap()

            assertEquals(DataStoreError.EntityNotFound, result.unwrapErr())
        }
    }

    @Test
    fun `apps updatePubliclyListed updates publiclyListed for existing app`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.apps.saveWithDefaultListing(
                    App("app1", "org1", "appListing1", false),
                    AppListing("appListing1", "app1", ListingLanguage.EN_US),
                ).bind()
            }.unwrap2()

            dataStore
                .runTxWithRetry { tx -> tx.apps.updatePubliclyListed("app1", true).bind() }
                .unwrap2()
            val updatedApp = dataStore
                .runTxWithRetry { tx -> tx.apps.findById("app1").bind() }
                .unwrap2()
                .unwrap()

            assertEquals(true, updatedApp.publiclyListed)
        }
    }

    @ParameterizedTest
    @MethodSource("authzHasPermissionReturnsTrueWithMinimalRelationshipsTestCases")
    fun `authz hasPermission returns true with minimal relationships`(
        testCase: AuthzHasPermissionReturnsTrueWithMinimalRelationships,
    ) {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                testCase.saveRelationships(tx).bind()

                val result = tx.authz.hasPermission(testCase.hasPermissionRequest).bind()

                assertTrue(result)
            }.unwrap2()
        }
    }

    @Test
    fun `appPackages saveFromPendingUpload returns ConsistencyViolationError for a mismatched blob service`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob())
                        .bind()
                }
                .unwrap2()

            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.appPackages.saveFromPendingUpload(
                        pendingUploadId = "appDraftUpload1",
                        appPackage = appPackage(),
                        permissions = emptyMap(),
                        blobVersion = ExternalBlob.GcsBlobVersion(1, 1),
                        replacedBlobDeleteTime = UNIX_EPOCH,
                    )
                        .bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.ConsistencyViolation, error)
        }
    }

    @Test
    fun `appPackages saveFromPendingUpload marks a replaced package's blob as deleted`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(tx).bind()
                    // A draft holds one pending upload at a time, so the committed one makes way
                    // for the upload the replacement package comes from
                    tx.appDrafts.deletePendingUploadByAppDraftId("appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(
                        tx,
                        appPackage = appPackage(id = "appPackage2", externalBlobId = "blob2"),
                        pendingUploadId = "appDraftUpload2",
                        objectKey = "object2",
                    )
                        .bind()
                }
                .unwrap2()

            val replacedBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap2()
            val replacementPackage = dataStore
                .runTxWithRetry { tx -> tx.appPackages.findByAppDraftId("appDraft1").bind() }
                .unwrap2()

            assertEquals(
                ExternalBlob.Status.Deleted(Some(ExternalBlob.LocalBlobVersion(1)), UNIX_EPOCH),
                replacedBlob.status,
            )
            assertEquals(Some("appPackage2"), replacementPackage.map { it.id })
        }
    }

    @Test
    fun `appDrafts completePendingUpload marks the released blob as deleted without a version`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob())
                        .bind()
                }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts
                        .completePendingUpload(
                            "appDraftUpload1",
                            AppDraftUploadProcessingError.AppDraftSubmitted,
                            UNIX_EPOCH,
                        )
                        .bind()
                }
                .unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap2()

            assertEquals(ExternalBlob.Status.Deleted(None, UNIX_EPOCH), foundBlob.status)
        }
    }

    @Test
    fun `appDrafts deletePendingUploadByAppDraftId marks the released blob as deleted`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob())
                        .bind()
                }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.deletePendingUploadByAppDraftId("appDraft1", UNIX_EPOCH).bind()
                }
                .unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap2()

            assertEquals(ExternalBlob.Status.Deleted(None, UNIX_EPOCH), foundBlob.status)
        }
    }

    @Test
    fun `appDrafts deleteById marks the app package's blob as deleted`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(tx).bind()
                }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx -> tx.appDrafts.deleteById("appDraft1", UNIX_EPOCH).bind() }
                .unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap2()

            assertEquals(
                ExternalBlob.Status.Deleted(Some(ExternalBlob.LocalBlobVersion(1)), UNIX_EPOCH),
                foundBlob.status,
            )
        }
    }

    @Test
    fun `appDrafts deleteById marks the pending upload's blob as deleted`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob())
                        .bind()
                }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx -> tx.appDrafts.deleteById("appDraft1", UNIX_EPOCH).bind() }
                .unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap2()

            assertEquals(ExternalBlob.Status.Deleted(None, UNIX_EPOCH), foundBlob.status)
        }
    }

    @Test
    fun `appDrafts deleteById marks a listing icon upload's blob as deleted`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx).bind()
                    tx.appDrafts
                        .saveListingIconUpload(
                            incompletePendingAppDraftListingIconUpload(),
                            pendingExternalBlob(),
                        )
                        .bind()
                }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx -> tx.appDrafts.deleteById("appDraft1", UNIX_EPOCH).bind() }
                .unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap2()

            assertEquals(ExternalBlob.Status.Deleted(None, UNIX_EPOCH), foundBlob.status)
        }
    }

    @Test
    fun `appDrafts deletePendingListingIconUploadByListingId marks the released blob as deleted`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx).bind()
                    tx.appDrafts
                        .saveListingIconUpload(
                            incompletePendingAppDraftListingIconUpload(),
                            pendingExternalBlob(),
                        )
                        .bind()
                }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts
                        .deletePendingListingIconUploadByListingId("appDraftListing1", UNIX_EPOCH)
                        .bind()
                }
                .unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap2()

            assertEquals(ExternalBlob.Status.Deleted(None, UNIX_EPOCH), foundBlob.status)
        }
    }

    @Test
    fun `appDrafts deleteListingById marks the pending icon upload's blob as deleted`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx).bind()
                    tx.appDrafts
                        .saveListingIconUpload(
                            incompletePendingAppDraftListingIconUpload(),
                            pendingExternalBlob(),
                        )
                        .bind()
                }
                .unwrap2()

            dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.deleteListingById("appDraftListing1", UNIX_EPOCH).bind()
                }
                .unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap2()

            assertEquals(ExternalBlob.Status.Deleted(None, UNIX_EPOCH), foundBlob.status)
        }
    }

    @Test
    fun `externalBlobs findById returns None when no blob with the given ID exists`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.findById("blob1").bind() }
                .unwrap2()

            assertTrue(result.isNone())
        }
    }

    @Test
    fun `externalBlobs findById round-trips a blob saved with its pending upload`() {
        withMigratedDataStore { dataStore ->
            val originalBlob = pendingExternalBlob()

            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts.saveUpload(incompletePendingAppDraftUpload(), originalBlob).bind()
                }
                .unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.findById(originalBlob.id).bind() }
                .unwrap2()

            assertEquals(Some(originalBlob), foundBlob)
        }
    }

    @Test
    fun `appDrafts saveUpload returns ConsistencyViolationError for a blob at an occupied location`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob())
                        .bind()
                }
                .unwrap2()

            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.appDrafts.saveUpload(
                        incompletePendingAppDraftUpload(
                            id = "appDraftUpload2",
                            appDraftId = "appDraft2",
                            externalBlobId = "blob2",
                            objectKey = "object2",
                        ),
                        pendingExternalBlob(id = "blob2", objectKey = "object1"),
                    )
                        .bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.ConsistencyViolation, error)
        }
    }

    @Test
    fun `appDrafts saveUpload allows the same location on a different service`() {
        withMigratedDataStore { dataStore ->
            val gcsBlob = ExternalBlob.Gcs(
                id = "blob2",
                createTime = UNIX_EPOCH,
                bucketName = "bucket1",
                objectKey = "object1",
                status = ExternalBlob.Status.Pending,
            )

            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft2", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(incompletePendingAppDraftUpload(), pendingExternalBlob())
                        .bind()
                    tx.appDrafts.saveUpload(
                        incompletePendingAppDraftUpload(
                            id = "appDraftUpload2",
                            appDraftId = "appDraft2",
                            externalBlobId = "blob2",
                            objectKey = "object2",
                        ),
                        gcsBlob,
                    )
                        .bind()
                }
                .unwrap2()

            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.findById("blob2").bind() }
                .unwrap2()

            assertEquals(Some(gcsBlob), foundBlob)
        }
    }

    @Test
    fun `organizations findIdByOwnerUserId returns ID of organization the user owns`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations
                    .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                    .bind()
                tx.organizations
                    .saveWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH)
                    .bind()
            }
                .unwrap2()

            val organizationId = dataStore
                .runTxWithRetry { tx -> tx.organizations.findIdByOwnerUserId("user1").bind() }
                .unwrap2()

            assertEquals(Some("org1"), organizationId)
        }
    }

    @Test
    fun `organizations findIdByOwnerUserId returns None for non-existent user`() {
        withMigratedDataStore { dataStore ->
            val organizationId = dataStore
                .runTxWithRetry { tx -> tx.organizations.findIdByOwnerUserId("user1").bind() }
                .unwrap2()

            assertEquals(None, organizationId)
        }
    }

    @Test
    fun `organizations saveWithOwner returns ConsistencyViolationError for duplicate organization ID`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                }
                .unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user2", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `organizations saveWithOwner returns ConsistencyViolationError for duplicate user ID`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                }
                .unwrap2()

            val result = dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org2", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @Test
    fun `organizations saveWithOwner returns ConsistencyViolationError for duplicate external user ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runTxWithRetry { tx ->
                tx.organizations
                    .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                    .bind()
            }
                .unwrap2()

            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org2", "user2", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.ConsistencyViolation, error)
        }
    }

    @Test
    fun `sessions create returns ConsistencyViolation if session with same ID hash already exists`() {
        withMigratedDataStore { dataStore ->
            val idHash = Sha256Hash.hash("session1".toByteArray())

            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                    tx.sessions.create(idHash, "user1", UNIX_EPOCH, UNIX_EPOCH.plusDays(1)).bind()
                }
                .unwrap2()

            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.sessions.create(idHash, "user1", UNIX_EPOCH, UNIX_EPOCH.plusDays(1)).bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.ConsistencyViolation, error)
        }
    }

    @Test
    fun `sessions create returns ConsistencyViolation if user does not exist`() {
        withMigratedDataStore { dataStore ->
            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.sessions
                        .create(
                            Sha256Hash.hash("session1".toByteArray()),
                            "user1",
                            UNIX_EPOCH,
                            UNIX_EPOCH.plusDays(1),
                        )
                        .bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.ConsistencyViolation, error)
        }
    }

    @Test
    fun `sessions create returns ConsistencyViolation if expireTime is not later than createTime`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                }
                .unwrap2()

            val error = dataStore
                .runTxWithRetry { tx ->
                    tx.sessions
                        .create(Sha256Hash.hash("session1".toByteArray()), "user1", UNIX_EPOCH, UNIX_EPOCH)
                        .bind()
                }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.ConsistencyViolation, error)
        }
    }

    @Test
    fun `users findIdByExternalUserId returns ID of user associated with given external user ID`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                }
                .unwrap2()

            val userId = dataStore
                .runTxWithRetry { tx -> tx.users.findIdByExternalUserId(ExternalUserId.Github(1)).bind() }
                .unwrap2()
                .unwrap()

            assertEquals("user1", userId)
        }
    }

    @Test
    fun `users findIdByExternalUserId returns None for non-existent user`() {
        withMigratedDataStore { dataStore ->
            val userId = dataStore
                .runTxWithRetry { tx -> tx.users.findIdByExternalUserId(ExternalUserId.Github(1)).bind() }
                .unwrap2()

            assertTrue(userId.isNone())
        }
    }

    @Test
    fun `users findIdBySessionIdHash returns ID of user associated with given session ID hash`() {
        withMigratedDataStore { dataStore ->
            val idHash = Sha256Hash.hash("session1".toByteArray())

            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                    tx.sessions.create(idHash, "user1", UNIX_EPOCH, UNIX_EPOCH.plusDays(1)).bind()
                }
                .unwrap2()

            val userId = dataStore
                .runTxWithRetry { tx ->
                    tx.users.findIdBySessionIdHash(idHash, UNIX_EPOCH).bind()
                }
                .unwrap2()
                .unwrap()

            assertEquals("user1", userId)
        }
    }

    @Test
    fun `users findIdBySessionIdHash returns None for non-existent session`() {
        withMigratedDataStore { dataStore ->
            val userId = dataStore
                .runTxWithRetry { tx ->
                    tx.users
                        .findIdBySessionIdHash(Sha256Hash.hash("session1".toByteArray()), UNIX_EPOCH)
                        .bind()
                }
                .unwrap2()

            assertTrue(userId.isNone())
        }
    }

    @Test
    fun `users findIdBySessionIdHash returns None for expired session`() {
        withMigratedDataStore { dataStore ->
            val idHash = Sha256Hash.hash("session1".toByteArray())

            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                    tx.sessions.create(idHash, "user1", UNIX_EPOCH, UNIX_EPOCH.plusDays(1)).bind()
                }
                .unwrap2()

            val userId = dataStore
                .runTxWithRetry { tx ->
                    tx.users.findIdBySessionIdHash(idHash, UNIX_EPOCH.plusDays(1)).bind()
                }
                .unwrap2()

            assertTrue(userId.isNone())
        }
    }

    @ParameterizedTest(name = "{0} save returns ConsistencyViolation for {1}")
    @MethodSource("textColumnConstraintTestCases")
    fun `save returns ConsistencyViolationError for invalid text column`(
        case: TextColumnConstraintTestCase,
        invalidInput: InvalidCanonicalText,
    ) {
        withMigratedDataStore { dataStore ->
            val result = dataStore
                .runTxWithRetry { tx -> case.save(tx, invalidInput.value).bind() }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @ParameterizedTest(name = "{0} save returns ConsistencyViolation for an ID longer than 64 characters")
    @MethodSource("idColumnConstraintTestCases")
    fun `save returns ConsistencyViolationError for ID longer than 64 characters`(
        case: TextColumnConstraintTestCase,
    ) {
        withMigratedDataStore { dataStore ->
            val tooLongId = "a".repeat(65)

            val result = dataStore
                .runTxWithRetry { tx -> case.save(tx, tooLongId).bind() }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @ParameterizedTest(name = "{0} save returns ConsistencyViolation for an ID with a disallowed character")
    @MethodSource("idColumnConstraintTestCases")
    fun `save returns ConsistencyViolationError for ID with disallowed character`(
        case: TextColumnConstraintTestCase,
    ) {
        withMigratedDataStore { dataStore ->
            // IDs are restricted to ASCII letters, digits, and underscores. This is otherwise valid
            // canonical text whose only violation is the disallowed hyphen.
            val disallowedId = "id-1"

            val result = dataStore
                .runTxWithRetry { tx -> case.save(tx, disallowedId).bind() }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @ParameterizedTest(name = "{0} save returns ConsistencyViolation for text longer than its maximum length")
    @MethodSource("textLengthConstraintTestCases")
    fun `save returns ConsistencyViolationError for text longer than maximum length`(
        case: TextLengthConstraintTestCase,
    ) {
        withMigratedDataStore { dataStore ->
            val tooLongText = "a".repeat(case.maxCodePoints + 1)

            val result = dataStore
                .runTxWithRetry { tx -> case.save(tx, tooLongText).bind() }
                .unwrap()

            assertEquals(DataStoreError.ConsistencyViolation, result.unwrapErr())
        }
    }

    @ParameterizedTest(name = "{0} save returns success for text at its maximum length")
    @MethodSource("textLengthConstraintTestCases")
    fun `save returns success for text at maximum length`(
        case: TextLengthConstraintTestCase,
    ) {
        withMigratedDataStore { dataStore ->
            val maxLengthText = "a".repeat(case.maxCodePoints)

            val result = dataStore
                .runTxWithRetry { tx -> case.save(tx, maxLengthText).bind() }
                .unwrap()

            assertEquals(Unit.right(), result)
        }
    }

    @Test
    fun `runTxWithRetry isolation prevents G2 anomaly`() {
        withMigratedDataStore { dataStore ->
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                }
                .unwrap2()

            // Classic predicate-based write skew. Both transactions read the same
            // predicate ("count of active drafts in org1") and observe zero, then each
            // inserts a row satisfying that predicate. Under PL-3, the resulting
            // anti-dependency cycle must be broken, so exactly one insert can take
            // effect. Under snapshot isolation, both reads see the original snapshot
            // and both inserts can commit.
            //
            // Latches (not a CyclicBarrier) are used so that the synchronization is
            // idempotent across runTxWithRetry retries: under a serializable
            // implementation one attempt aborts with a SerializationFailure and is
            // re-executed, and a re-entry into the block must not block on a
            // synchronization primitive that the other thread has already passed.
            val t1Read = CountDownLatch(1)
            val t2Read = CountDownLatch(1)

            fun runTransaction(
                draftId: String,
                thisRead: CountDownLatch,
                otherRead: CountDownLatch,
            ) {
                dataStore.runTxWithRetry { tx ->
                    val count = tx.appDrafts.countActiveInOrganization("org1").bind()
                    thisRead.countDown()
                    otherRead.await(1, TimeUnit.SECONDS)
                    if (count == 0uL) {
                        tx.appDrafts.create("org1", draftId, UNIX_EPOCH).bind()
                    }
                }
                    .unwrap2()
            }

            val t1 = Thread { runTransaction("draft1", t1Read, t2Read) }
            val t2 = Thread { runTransaction("draft2", t2Read, t1Read) }
            t1.start()
            t2.start()
            t1.join(30_000)
            t2.join(30_000)
            assertFalse(t1.isAlive, "Thread 1 timed out")
            assertFalse(t2.isAlive, "Thread 2 timed out")

            val count = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.countActiveInOrganization("org1").bind() }
                .unwrap2()

            assertEquals(1uL, count)
        }
    }

    companion object {
        sealed class InvalidCanonicalText(val value: String) {
            data object Empty : InvalidCanonicalText("") {
                override fun toString() = "an empty string"
            }

            // U+FDD0 is a noncharacter according to the Unicode standard
            // (https://www.unicode.org/versions/Unicode17.0.0/core-spec/chapter-23/#G19653), so we
            // can permanently rely on it being unassigned
            data object ContainsUnassignedChar : InvalidCanonicalText("\ufdd0") {
                override fun toString() = "a string with an unassigned Unicode character"
            }

            // U+212B is an explicit example of non-NFC source text given in the Unicode standard
            // (https://www.unicode.org/reports/tr15/tr15-57.html#Canon_Compat_Equivalence), so we
            // can permanently rely on it being non-NFC
            data object NonNfc : InvalidCanonicalText("\u212b") {
                override fun toString() = "non-NFC text"
            }
        }

        class TextColumnConstraintTestCase(
            val column: String,
            val save: (DataStore.Transaction, String) -> DataStoreResult<Unit>,
        ) {
            override fun toString(): String = column
        }

        class TextLengthConstraintTestCase(
            val column: String,
            val maxCodePoints: Int,
            val save: (DataStore.Transaction, String) -> DataStoreResult<Unit>,
        ) {
            override fun toString(): String = column
        }

        @JvmStatic
        private fun idColumnConstraintTestCases(): List<TextColumnConstraintTestCase> = listOf(
            TextColumnConstraintTestCase("apps.id") { tx, invalid ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    // app.id and the listing's appId must match, so both carry the invalid value
                    tx.apps.saveWithDefaultListing(
                        App(invalid, "org1", "appListing1", false),
                        AppListing("appListing1", invalid, ListingLanguage.EN_US),
                    ).bind()
                }
            },
            TextColumnConstraintTestCase("appDrafts.id") { tx, invalid ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", invalid, UNIX_EPOCH).bind()
                }
            },
            TextColumnConstraintTestCase("appDraftListings.id") { tx, invalid ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx, id = invalid).bind()
                }
            },
            TextColumnConstraintTestCase("appListings.id") { tx, invalid ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.apps.saveWithDefaultListing(
                        App("app1", "org1", "appListing1", false),
                        AppListing(invalid, "app1", ListingLanguage.EN_US),
                    ).bind()
                }
            },
            TextColumnConstraintTestCase("appPackages.id") { tx, invalid ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    saveAppPackageFromNewUpload(tx, appPackage(id = invalid)).bind()
                }
            },
            TextColumnConstraintTestCase("organizations.id") { tx, invalid ->
                either {
                    tx.organizations.saveWithOwner(invalid, "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                }
            },
            TextColumnConstraintTestCase("pendingAppDraftUploads.id") { tx, invalid ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    tx.appDrafts
                        .saveUpload(
                            incompletePendingAppDraftUpload(id = invalid),
                            pendingExternalBlob(),
                        )
                        .bind()
                }
            },
            TextColumnConstraintTestCase("pendingAppDraftListingIconUploads.id") { tx, invalid ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx).bind()
                    tx.appDrafts
                        .saveListingIconUpload(
                            incompletePendingAppDraftListingIconUpload(id = invalid),
                            pendingExternalBlob(),
                        )
                        .bind()
                }
            },
            TextColumnConstraintTestCase("users.id") { tx, invalid ->
                either {
                    tx.organizations.saveWithOwner("org1", invalid, ExternalUserId.Github(1), UNIX_EPOCH).bind()
                }
            },
        )

        @JvmStatic
        private fun textLengthConstraintTestCases(): List<TextLengthConstraintTestCase> = listOf(
            TextLengthConstraintTestCase("appDraftListings.name", maxCodePoints = 30) { tx, tooLong ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx, name = tooLong).bind()
                }
            },
            TextLengthConstraintTestCase(
                "appDraftListings.shortDescription",
                maxCodePoints = 80,
            ) { tx, tooLong ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx, shortDescription = tooLong).bind()
                }
            },
        )

        @JvmStatic
        private fun textColumnConstraintTestCases(): List<Arguments> {
            val invalidInputs = listOf(
                InvalidCanonicalText.Empty,
                InvalidCanonicalText.ContainsUnassignedChar,
                InvalidCanonicalText.NonNfc,
            )

            // Foreign key columns are intentionally omitted here since their domains are already
            // limited as desired at the target column
            val cases = idColumnConstraintTestCases() + listOf(
                TextColumnConstraintTestCase("appDraftListings.name") { tx, invalid ->
                    either {
                        tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                        tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                        createAppDraftListing(tx, name = invalid).bind()
                    }
                },
                TextColumnConstraintTestCase("appDraftListings.shortDescription") { tx, invalid ->
                    either {
                        tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                        tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                        createAppDraftListing(tx, shortDescription = invalid).bind()
                    }
                },
                TextColumnConstraintTestCase("appPackages.versionName") { tx, invalid ->
                    either {
                        tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                        tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                        saveAppPackageFromNewUpload(
                            tx,
                            appPackage(versionName = VersionName.fromString(invalid).unwrap()),
                        )
                            .bind()
                    }
                },
                TextColumnConstraintTestCase("appPackagePermissions.name") { tx, invalid ->
                    either {
                        tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                        tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                        saveAppPackageFromNewUpload(
                            tx,
                            permissions = mapOf(NameAttribute.fromString(invalid).unwrap() to None),
                        )
                            .bind()
                    }
                },
                TextColumnConstraintTestCase("externalBlobs.bucketName") { tx, invalid ->
                    either {
                        tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                        tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                        tx.appDrafts.saveUpload(
                            incompletePendingAppDraftUpload(),
                            pendingExternalBlob(bucketName = invalid),
                        )
                            .bind()
                    }
                },
                TextColumnConstraintTestCase("externalBlobs.objectKey") { tx, invalid ->
                    either {
                        tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                        tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                        tx.appDrafts.saveUpload(
                            incompletePendingAppDraftUpload(),
                            pendingExternalBlob(objectKey = invalid),
                        )
                            .bind()
                    }
                },
                TextColumnConstraintTestCase("pendingAppDraftUploads.objectKey") { tx, invalid ->
                    either {
                        tx.appDrafts.saveUpload(
                            incompletePendingAppDraftUpload(objectKey = invalid),
                            pendingExternalBlob(),
                        )
                            .bind()
                    }
                },
            )

            return cases.flatMap { case ->
                invalidInputs.map { invalidInput -> Arguments.of(case, invalidInput) }
            }
        }

        @JvmStatic
        private fun appDraftUploadProcessingErrors(): List<AppDraftUploadProcessingError> = listOf(
            AppDraftUploadProcessingError.AppDraftSubmitted,
            AppDraftUploadProcessingError.ApkSetParseFailed(ApkSetParseError.InvalidFormat),
            AppDraftUploadProcessingError.ApkSetParseFailed(ApkSetParseError.Io),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Missing64BitCode,
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.LowTargetSdk,
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(ApkParseError.Policy.NoModernSignature),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(ApkParseError.Policy.SignedWithDebugCert),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(ApkParseError.Policy.SignedWithMultipleCerts),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(ApkParseError.Policy.Unverified),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(AndroidManifest.FromXmlError.Policy.DebuggableTrue),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(AndroidManifest.FromXmlError.Policy.TestOnlyTrue),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(
                        AndroidManifest.FromXmlError.Policy.MultipleApplicationElements,
                    ),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(
                        AndroidManifest.FromXmlError.Policy.MultipleUsesSdkElements,
                    ),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(AndroidManifest.FromXmlError.Policy.NoVersionCode),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(
                        AndroidManifest.FromXmlError.Policy.DuplicatePermission,
                    ),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(
                        AndroidManifest.FromXmlError.Policy.InvalidApplicationId,
                    ),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(
                        AndroidManifest.FromXmlError.Policy.PermissionMaxSdkOutOfRange,
                    ),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(
                        AndroidManifest.FromXmlError.Policy.PermissionNameTooLong,
                    ),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(
                        AndroidManifest.FromXmlError.Policy.VersionCodeOutOfRange,
                    ),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(
                        AndroidManifest.FromXmlError.Policy.VersionCodeMajorNonZero,
                    ),
                ),
            ),
            AppDraftUploadProcessingError.ApkSetParseFailed(
                ApkSetParseError.Policy.Apk(
                    ApkParseError.Policy.Manifest(
                        AndroidManifest.FromXmlError.Policy.VersionNameTooLong,
                    ),
                ),
            ),
        )

        data class AuthzHasPermissionReturnsTrueWithMinimalRelationships(
            val hasPermissionRequest: HasPermissionRequest,
            val saveRelationships: (DataStore.Transaction) -> DataStoreResult<Unit>,
        )

        @JvmStatic
        private fun authzHasPermissionReturnsTrueWithMinimalRelationshipsTestCases()
                : List<AuthzHasPermissionReturnsTrueWithMinimalRelationships> = listOf(
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.CreateAppDraft("org1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.CreateAppDraftListing("appDraft1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.DeleteAppDraft("appDraft1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.DeleteAppDraftListing("appDraftListing1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.DownloadAppDraft("appDraft1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.ReplaceAppDraftPackage("appDraft1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.SubmitAppDraft("appDraft1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.UpdateApp("app1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.apps.saveWithDefaultListing(
                        App("app1", "org1", "appListing1", false),
                        AppListing("appListing1", "app1", ListingLanguage.EN_US),
                    ).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.UpdateAppDraft("appDraft1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.UpdateAppDraftListing("appDraftListing1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.UploadAppDraftListingIcon("appDraftListing1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.ViewApp("app1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.apps.saveWithDefaultListing(
                        App("app1", "org1", "appListing1", false),
                        AppListing("appListing1", "app1", ListingLanguage.EN_US),
                    ).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.ViewAppDraft("appDraft1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                }
            },
            AuthzHasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.ViewAppDraftListing("appDraftListing1", "user1"),
            ) { tx ->
                either {
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                    createAppDraftListing(tx).bind()
                }
            },
        )
    }
}
