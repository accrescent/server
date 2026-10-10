// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

import app.accrescent.server.core.Error
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.crypto.Sha256Hash
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.raise.Raise
import java.time.OffsetDateTime

/**
 * An error which can occur when reading from or writing to a [DataStore].
 */
sealed class DataStoreError : Error() {
    /**
     * The operation could not be completed because it would result in a consistency violation in
     * the underlying data.
     */
    class ConsistencyViolation : DataStoreError() {
        override val message = "consistency violation"
    }

    /**
     * The data store is in an unexpected state which should not normally be reachable, e.g., a
     * database schema was modified externally.
     */
    class IllegalState : DataStoreError() {
        override val message = "illegal state encountered"
    }

    /**
     * Transaction serialization failed.
     */
    class SerializationFailure : DataStoreError() {
        override val message = "serialization failure"
    }

    /**
     * An unknown error occurred.
     */
    class Unknown : DataStoreError() {
        override val message = "unknown error"
    }
}

/**
 * A specialized [Either] type for [DataStore] operations.
 */
typealias DataStoreResult<T> = Either<DataStoreError, T>

/**
 * A persistent data store for application data.
 *
 * Implementations provide [ACID](https://en.wikipedia.org/wiki/ACID) properties and so are
 * typically backed by a transactional RDBMS.
 */
interface DataStore {
    /**
     * Applies all the data store's pending migrations.
     *
     * This method is idempotent and will return successfully if the data store is known to have no
     * remaining pending migrations. Concurrent calls for the same backing data store will block
     * until migration is complete.
     */
    fun migrateToHead(): DataStoreResult<Unit>

    /**
     * Runs the given lambda in the context of a transaction.
     *
     * All methods accessible through the [Transaction] provided in [block] participate in the
     * transaction if they are called within [block]'s scope. Calling any such methods outside of
     * [block]'s scope results in undefined behavior.
     *
     * The transaction is committed if [block] returns normally. If [block] raises or throws, the
     * transaction is rolled back.
     *
     * The transaction's isolation level is equivalent to the PL-3 isolation level defined in
     * [Generalized Isolation Level Definitions](https://doi.org/10.1109/ICDE.2000.839388) by Adya
     * et al. Less formally, it provides true serializability, which is distinct from and stronger
     * than some database implementations of the SQL standard SERIALIZABLE isolation level in that
     * it guarantees that concurrent execution of a set of serializable transactions will produce
     * the same effect as running them one at a time in some order. Snapshot isolation on its own,
     * notably, does not meet this standard.
     *
     * @param block a lambda to run in the context of the data store transaction.
     * @return the value returned by [block] wrapped in the [DataStoreResult] of executing the
     * transaction.
     */
    suspend fun <T, E> runInTransaction(
        block: suspend Raise<E>.(Transaction) -> T,
    ): DataStoreResult<Either<E, T>>

    /**
     * A data store transaction.
     *
     * Provides access to all data access methods which participate in a data store transaction.
     */
    interface Transaction {
        /**
         * Creates an organization with its owner.
         *
         * @param organizationId the ID of the organization to create.
         * @param userId the ID of the user to create.
         * @param externalUserId the external identity of the user.
         * @param createTime the creation timestamp to set for the new organization and user.
         * @return [DataStoreError.ConsistencyViolation] if an organization with the same ID, a user
         * with the same ID, or a user with the same external user ID already exists.
         */
        suspend fun createOrgWithOwner(
            organizationId: String,
            userId: String,
            externalUserId: ExternalUserId,
            createTime: OffsetDateTime,
        ): DataStoreResult<Unit>

        /**
         * Creates a new login session for a given user.
         *
         * @param idHash the SHA-256 hash of the session ID to store.
         * @param userId the ID of the user to associate the session with.
         * @param createTime the creation timestamp to set for the new session.
         * @param expireTime the expiration timestamp to set for the new session after which it will
         * no longer be valid.
         * @return [DataStoreError.ConsistencyViolation] if a session with the same ID hash already
         * exists, the user does not exist, or [expireTime] is not later than [createTime].
         */
        suspend fun createSession(
            idHash: Sha256Hash,
            userId: String,
            createTime: OffsetDateTime,
            expireTime: OffsetDateTime,
        ): DataStoreResult<Unit>

        /**
         * Finds the ID of a user with a given external user identity.
         *
         * @param externalUserId the external user ID of the user to find the ID of.
         * @return the ID of the user with the given external user identity, or [None] if none
         * exists.
         */
        suspend fun findUserIdByExternalUserId(
            externalUserId: ExternalUserId,
        ): DataStoreResult<Option<String>>
    }
}
