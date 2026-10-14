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
import arrow.core.flatten
import arrow.core.raise.either
import arrow.core.right
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

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
     * Whether the data store under test can run multiple transactions concurrently.
     *
     * Tests which require concurrent transactions to interleave are skipped when this is `false`
     * since waiting for such an interleaving would deadlock a data store which runs transactions
     * one at a time.
     */
    protected open val runsTransactionsConcurrently = true

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
    suspend fun `runInTransaction prevents write skew`() {
        assumeTrue(runsTransactionsConcurrently, "data store runs transactions one at a time")

        withMigratedDataStore { dataStore ->
            val readsDone = List(2) { CompletableDeferred<Unit>() }

            // Each transaction creates its user only if the other transaction's user doesn't exist,
            // so no serial execution of the two transactions can create both users. Snapshot
            // isolation, however, allows creating both users since both reads precede both writes.
            suspend fun createUserIfOtherAbsent(n: Int, other: Int) = try {
                dataStore.runInTransaction { tx ->
                    val otherUserId =
                        tx.findUserIdByExternalUserId(ExternalUserId.Github(other.toLong())).bind()

                    // Wait for the other transaction's read so that both reads precede both writes
                    readsDone[n - 1].complete(Unit)
                    readsDone.awaitAll()

                    if (otherUserId.isNone()) {
                        tx.createOrgWithOwner(
                            "org$n",
                            "user$n",
                            ExternalUserId.Github(n.toLong()),
                            UNIX_EPOCH,
                        )
                            .bind()
                    }
                }
            } finally {
                // Don't leave the other transaction waiting forever if this one failed to read
                readsDone[n - 1].complete(Unit)
            }

            val results = coroutineScope {
                listOf(
                    async { createUserIfOtherAbsent(1, 2) },
                    async { createUserIfOtherAbsent(2, 1) },
                )
                    .awaitAll()
            }

            // The data store may abort a transaction with a serialization error, but not for any
            // other reason
            results
                .mapNotNull { it.flatten().leftOrNull() }
                .forEach { assertInstanceOf<DataStoreError.SerializationFailure>(it) }
            val userIds = dataStore.runInTransaction { tx ->
                (1L..2L).map { tx.findUserIdByExternalUserId(ExternalUserId.Github(it)).bind() }
            }
                .unwrap2()
            assertTrue(userIds.count { it.isSome() } <= 1, "both users were created: $userIds")
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
    suspend fun `findOrgApiViewById returns API view of organization with given ID`() {
        withMigratedDataStore { dataStore ->
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap2()

            val organization = dataStore
                .runInTransaction { tx -> tx.findOrgApiViewById("org1").bind() }
                .unwrap2()

            assertEquals(Some(OrgApiView("org1", 0u, 0u)), organization)
        }
    }

    @Test
    suspend fun `findOrgApiViewById returns None for non-existent organization`() {
        withMigratedDataStore { dataStore ->
            val organization = dataStore
                .runInTransaction { tx -> tx.findOrgApiViewById("org1").bind() }
                .unwrap2()

            assertEquals(None, organization)
        }
    }

    @Test
    suspend fun `findOrgIdByOwnerUserId returns ID of organization owned by given user`() {
        withMigratedDataStore { dataStore ->
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
            }
                .unwrap2()

            val organizationId = dataStore.runInTransaction { tx ->
                tx.findOrgIdByOwnerUserId("user1").bind()
            }
                .unwrap2()

            assertEquals(Some("org1"), organizationId)
        }
    }

    @Test
    suspend fun `findOrgIdByOwnerUserId returns None for non-existent user`() {
        withMigratedDataStore { dataStore ->
            val organizationId = dataStore.runInTransaction { tx ->
                tx.findOrgIdByOwnerUserId("user2").bind()
            }
                .unwrap2()

            assertEquals(None, organizationId)
        }
    }

    @Test
    suspend fun `findUserIdByActiveSessionIdHash returns ID of user associated with given session ID hash`() {
        withMigratedDataStore { dataStore ->
            val idHash = Sha256Hash.hash("session1".toByteArray())
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.createSession(idHash, "user1", UNIX_EPOCH, UNIX_EPOCH.plusDays(1)).bind()
            }
                .unwrap2()

            val userId = dataStore.runInTransaction { tx ->
                tx.findUserIdByActiveSessionIdHash(idHash, UNIX_EPOCH).bind()
            }
                .unwrap2()

            assertEquals(Some("user1"), userId)
        }
    }

    @Test
    suspend fun `findUserIdByActiveSessionIdHash returns None for non-existent session`() {
        withMigratedDataStore { dataStore ->
            val userId = dataStore.runInTransaction { tx ->
                tx.findUserIdByActiveSessionIdHash(
                    Sha256Hash.hash("session1".toByteArray()),
                    UNIX_EPOCH,
                )
                    .bind()
            }
                .unwrap2()

            assertEquals(None, userId)
        }
    }

    @Test
    suspend fun `findUserIdByActiveSessionIdHash returns None for expired session`() {
        withMigratedDataStore { dataStore ->
            val idHash = Sha256Hash.hash("session1".toByteArray())
            dataStore.runInTransaction { tx ->
                tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                tx.createSession(idHash, "user1", UNIX_EPOCH, UNIX_EPOCH.plusDays(1)).bind()
            }
                .unwrap2()

            val userId = dataStore.runInTransaction { tx ->
                tx.findUserIdByActiveSessionIdHash(idHash, UNIX_EPOCH.plusDays(1)).bind()
            }
                .unwrap2()

            assertEquals(None, userId)
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

    @ParameterizedTest
    @MethodSource("hasPermissionReturnsTrueWithMinimalRelationshipsTestCases")
    suspend fun `hasPermission returns true with minimal relationships`(
        testCase: HasPermissionReturnsTrueWithMinimalRelationships,
    ) {
        withMigratedDataStore { dataStore ->
            val result = dataStore.runInTransaction { tx ->
                testCase.saveRelationships(tx).bind()

                tx.hasPermission(testCase.hasPermissionRequest).bind()
            }
                .unwrap2()

            assertTrue(result)
        }
    }

    companion object {
        data class HasPermissionReturnsTrueWithMinimalRelationships(
            val hasPermissionRequest: HasPermissionRequest,
            val saveRelationships: suspend (DataStore.Transaction) -> DataStoreResult<Unit>,
        )

        @JvmStatic
        private fun hasPermissionReturnsTrueWithMinimalRelationshipsTestCases()
                : List<HasPermissionReturnsTrueWithMinimalRelationships> = listOf(
            HasPermissionReturnsTrueWithMinimalRelationships(
                HasPermissionRequest.ViewOrganization("org1", "user1"),
            ) { tx ->
                either {
                    tx.createOrgWithOwner("org1", "user1", ExternalUserId.Github(1), UNIX_EPOCH).bind()
                }
            },
        )
    }
}
