// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

import app.accrescent.server.UNIX_EPOCH
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrap2
import app.accrescent.server.core.unwrapErr
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.crypto.Sha256Hash
import arrow.core.None
import arrow.core.Some
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows

/**
 * Conformance test suite for [DataStore] implementations.
 */
abstract class DataStoreConformanceTest {
    /**
     * Runs a lambda with a new [DataStore] instance.
     *
     * Each call creates a new data store instance which shares no state with any other instance.
     * Attempts to call any methods on the [DataStore] outside of [block] result in undefined
     * behavior.
     *
     * @param block the lambda to run with access to a new [DataStore] instance.
     * @return the return value of [block].
     */
    protected abstract suspend fun <T> withDataStore(block: suspend (DataStore) -> T): T

    /**
     * Convenience method for running a lambda with a new, migrated [DataStore] instance.
     *
     * This method has almost the same behavior as [withDataStore], but migrates the [DataStore] to
     * head before running [block]. If migrating fails, this method will throw.
     *
     * @param block the lambda to run with access to a new, migrated [DataStore] instance.
     * @return the return value of [block].
     * @throws Throwable if migrating the data store fails.
     */
    suspend fun <T> withMigratedDataStore(block: suspend (DataStore) -> T): T {
        return withDataStore { dataStore ->
            dataStore.migrateToHead().unwrap()
            block(dataStore)
        }
    }

    @Test
    suspend fun `migrateToHead returns successfully if migrations are up-to-date`() {
        withDataStore { dataStore ->
            dataStore.migrateToHead().unwrap()
            dataStore.migrateToHead().unwrap()
        }
    }

    @Test
    suspend fun `runInTransaction returns the value returned by block`() {
        withMigratedDataStore { dataStore ->
            val result = dataStore.runInTransaction<_, Nothing> { "result" }.unwrap2()

            assertEquals("result", result)
        }
    }

    @Test
    suspend fun `runInTransaction propagates exception thrown by block`() {
        class TestException : Exception()

        withMigratedDataStore { dataStore ->
            assertThrows<TestException> {
                dataStore.runInTransaction<_, Nothing> { throw TestException() }.unwrap2()
            }
        }
    }

    @Test
    suspend fun `runInTransaction rolls back write when block throws`() {
        withMigratedDataStore { dataStore ->
            assertThrows<Exception> {
                dataStore.runInTransaction { tx ->
                    tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                    throw Exception()
                }
                    .unwrap2()
            }

            // Creating this data again can succeed only if the previous transaction rolled back
            // since organization IDs, user IDs, and external user IDs must be unique
            val result = dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }

            assertEquals(Unit.right().right(), result)
        }
    }

    @Test
    suspend fun `runInTransaction rolls back write when block raises an error`() {
        withMigratedDataStore { dataStore ->
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                raise(DataStoreError.Unknown())
            }
                .unwrap()
                .unwrapErr()

            // Creating this data again can succeed only if the previous transaction rolled back
            // since organization IDs, user IDs, and external user IDs must be unique
            val result = dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }

            assertEquals(Unit.right().right(), result)
        }
    }

    @Test
    suspend fun `runInTransaction commits write when block completes successfully`() {
        withMigratedDataStore { dataStore ->
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap2()

            // Creating this data again will fail with a consistency violation only if the previous
            // transaction committed since organization IDs, user IDs, and external user IDs must be
            // unique
            val error = dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap()
                .unwrapErr()

            assertInstanceOf<DataStoreError.ConsistencyViolation>(error)
        }
    }

    @Test
    suspend fun `createOrgWithOwner returns ConsistencyViolation if org with same ID already exists`() {
        withMigratedDataStore { dataStore ->
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap2()

            val error = dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
            }
                .unwrap()
                .unwrapErr()

            assertInstanceOf<DataStoreError.ConsistencyViolation>(error)
        }
    }

    @Test
    suspend fun `createOrgWithOwner returns ConsistencyViolation if user with same ID already exists`() {
        withMigratedDataStore { dataStore ->
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap2()

            val error = dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org2", "user1", ExternalUserId.Github(2), UNIX_EPOCH).bind()
            }
                .unwrap()
                .unwrapErr()

            assertInstanceOf<DataStoreError.ConsistencyViolation>(error)
        }
    }

    @Test
    suspend fun `createOrgWithOwner returns ConsistencyViolation if user with same external user ID already exists`() {
        withMigratedDataStore { dataStore ->
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap2()

            val error = dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org2", "user2", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap()
                .unwrapErr()

            assertInstanceOf<DataStoreError.ConsistencyViolation>(error)
        }
    }

    @Test
    suspend fun `createSession returns ConsistencyViolation if session with same ID already exists`() {
        withMigratedDataStore { dataStore ->
            val idHash = Sha256Hash.hash("session1".toByteArray())
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.createOrgWithOwner("org2", "user2", ExternalUserId.Github(2), UNIX_EPOCH).bind()
                tx.createSession(idHash, "user1", UNIX_EPOCH, UNIX_EPOCH.plusDays(1)).bind()
            }
                .unwrap2()

            val error = dataStore.runInTransaction { tx ->
                tx.createSession(idHash, "user2", UNIX_EPOCH, UNIX_EPOCH.plusDays(1)).bind()
            }
                .unwrap()
                .unwrapErr()

            assertInstanceOf<DataStoreError.ConsistencyViolation>(error)
        }
    }

    @Test
    suspend fun `createSession returns ConsistencyViolation if user does not exist`() {
        withMigratedDataStore { dataStore ->
            val idHash = Sha256Hash.hash("session1".toByteArray())

            val error = dataStore.runInTransaction { tx ->
                tx.createSession(idHash, "user1", UNIX_EPOCH, UNIX_EPOCH.plusDays(1)).bind()
            }
                .unwrap()
                .unwrapErr()

            assertInstanceOf<DataStoreError.ConsistencyViolation>(error)
        }
    }

    @Test
    suspend fun `createSession returns ConsistencyViolation if expireTime is not later than createTime`() {
        withMigratedDataStore { dataStore ->
            val idHash = Sha256Hash.hash("session1".toByteArray())
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap2()

            val error = dataStore.runInTransaction { tx ->
                tx.createSession(idHash, "user1", UNIX_EPOCH, UNIX_EPOCH).bind()
            }
                .unwrap()
                .unwrapErr()

            assertInstanceOf<DataStoreError.ConsistencyViolation>(error)
        }
    }

    @Test
    suspend fun `findUserIdByExternalUserId returns ID of user associated with given external user ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap2()

            val userId = dataStore.runInTransaction { tx ->
                tx.findUserIdByExternalUserId(ExternalUserId.Github(1)).bind()
            }
                .unwrap2()

            assertEquals(Some("user1"), userId)
        }
    }

    @Test
    suspend fun `findUserIdByExternalUserId returns None for non-existent user`() {
        withMigratedDataStore { dataStore ->
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap2()

            val userId = dataStore.runInTransaction { tx ->
                tx.findUserIdByExternalUserId(ExternalUserId.Github(2)).bind()
            }
                .unwrap2()

            assertEquals(None, userId)
        }
    }
}
