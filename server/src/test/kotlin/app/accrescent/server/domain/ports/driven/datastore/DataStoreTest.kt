// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

import app.accrescent.server.UNIX_EPOCH
import app.accrescent.server.adapters.driven.datastore.jdbc.InMemoryDataStore
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.appDraftListingApiView
import app.accrescent.server.committedExternalBlob
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrap2
import app.accrescent.server.core.unwrapErr
import app.accrescent.server.createAppDraftListing
import app.accrescent.server.domain.appstore.ListingLanguage
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.incompletePendingAppDraftUpload
import app.accrescent.server.pendingExternalBlob
import app.accrescent.server.saveAppPackageFromNewUpload
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class DataStoreTest {
    @Test
    fun `runTxWithRetry retries up to 5 times on SerializationFailure`() {
        var callCount = 0
        val store = object : DataStore(DeterministicRandomSource()) {
            override fun migrateToHead() = Unit.right()

            override fun <T, E> runInTransaction(
                block: Raise<E>.(Transaction) -> T,
            ): DataStoreResult<Either<E, T>> {
                callCount++
                return DataStoreError.SerializationFailure.left()
            }
        }

        store.runTxWithRetry<_, Nothing> { "anything" }.unwrapErr()

        assertEquals(6, callCount)
    }

    @Test
    fun `runTxWithRetry returns SerializationFailure when retries are exhausted`() {
        val store = object : DataStore(DeterministicRandomSource()) {
            override fun migrateToHead(): DataStoreResult<Unit> = Unit.right()

            override fun <T, E> runInTransaction(
                block: Raise<E>.(Transaction) -> T,
            ): DataStoreResult<Either<E, T>> =
                DataStoreError.SerializationFailure.left()
        }

        val result = store.runTxWithRetry<_, Nothing> { "anything" }

        assertInstanceOf<DataStoreError.SerializationFailure>(result.unwrapErr())
    }

    @Test
    fun `appDrafts requireApiViewById returns EntityNotFound if app draft does not exist`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()

            val error = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.requireApiViewById("appDraft1").bind() }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `appDrafts requireListingApiViewById returns EntityNotFound if listing does not exist`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()

            val error = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.requireListingApiViewById("appDraftListing1").bind() }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `appDrafts requireListingApiViewById returns app draft listing persisted with createListing`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                }
                .unwrap2()

            dataStore.runTxWithRetry { tx -> createAppDraftListing(tx).bind() }.unwrap2()
            val foundListing = dataStore
                .runTxWithRetry { tx -> tx.appDrafts.requireListingApiViewById("appDraftListing1").bind() }
                .unwrap2()

            assertEquals(appDraftListingApiView(), foundListing)
        }
    }

    @Test
    fun `apps requireById returns EntityNotFound if app does not exist`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()

            val error = dataStore
                .runTxWithRetry { tx -> tx.apps.requireById("app1").bind() }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `apps requireById returns app persisted with save`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val originalApp = App("app1", "org1", "appListing1", false)
            dataStore
                .runTxWithRetry { tx ->
                    tx.organizations
                        .saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH)
                        .bind()
                }
                .unwrap2()

            val defaultListing = AppListing("appListing1", "app1", ListingLanguage.EN_US)
            dataStore.runTxWithRetry { tx ->
                tx.apps.saveWithDefaultListing(originalApp, defaultListing).bind()
            }
                .unwrap2()
            val foundApp = dataStore
                .runTxWithRetry { tx -> tx.apps.requireById("app1").bind() }
                .unwrap2()

            assertEquals(originalApp, foundApp)
        }
    }

    @Test
    fun `externalBlobs requireById returns EntityNotFound if blob does not exist`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()

            val error = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById("blob1").bind() }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `externalBlobs requireById returns blob persisted with saveUpload`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val originalBlob = pendingExternalBlob()

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.saveUpload(incompletePendingAppDraftUpload(), originalBlob).bind()
            }.unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireById(originalBlob.id).bind() }
                .unwrap2()

            assertEquals(originalBlob, foundBlob)
        }
    }

    @Test
    fun `externalBlobs requireCommittedById returns EntityNotFound if blob does not exist`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()

            val error = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireCommittedById("blob1").bind() }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `externalBlobs requireCommittedById returns EntityNotFound if blob is not committed`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val pendingBlob = pendingExternalBlob()

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                tx.appDrafts.saveUpload(incompletePendingAppDraftUpload(), pendingBlob).bind()
            }.unwrap2()
            val error = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireCommittedById(pendingBlob.id).bind() }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }

    @Test
    fun `externalBlobs requireCommittedById returns the blob an app package committed`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val originalBlob = committedExternalBlob()

            dataStore.runTxWithRetry { tx ->
                tx.organizations.saveWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.appDrafts.create("org1", "appDraft1", UNIX_EPOCH).bind()
                saveAppPackageFromNewUpload(tx).bind()
            }.unwrap2()
            val foundBlob = dataStore
                .runTxWithRetry { tx -> tx.externalBlobs.requireCommittedById(originalBlob.id).bind() }
                .unwrap2()

            assertEquals(originalBlob, foundBlob)
        }
    }

    @Test
    fun `organizations requireIdByOwnerUserId returns EntityNotFound if user does not exist`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()

            val error = dataStore
                .runTxWithRetry { tx -> tx.organizations.requireIdByOwnerUserId("user1").bind() }
                .unwrap()
                .unwrapErr()

            assertEquals(DataStoreError.EntityNotFound, error)
        }
    }
}
