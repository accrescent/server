// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

import app.accrescent.server.core.Error
import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.PositiveInt
import app.accrescent.server.domain.appstore.AppName
import app.accrescent.server.domain.appstore.AppShortDescription
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.crypto.Sha256Hash
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.flatMap
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
     * An entity which was expected to exist was not found.
     */
    class EntityNotFound : DataStoreError() {
        override val message = "entity not found"
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
    abstract class Transaction {
        /**
         * Counts the app drafts in an organization.
         *
         * @param organizationId the ID of the organization to count the app drafts of.
         * @return the number of app drafts in the organization, or zero if the organization does
         * not exist.
         */
        abstract suspend fun countAppDraftsInOrg(
            organizationId: String,
        ): DataStoreResult<NonNegativeInt>

        /**
         * Creates an app draft.
         *
         * @param appDraftId the ID of the app draft to create.
         * @param organizationId the ID of the organization to create the app draft in.
         * @param createTime the creation timestamp to set for the new app draft.
         * @return [DataStoreError.ConsistencyViolation] if an app draft with the same ID already
         * exists or the organization does not exist.
         */
        abstract suspend fun createAppDraft(
            appDraftId: String,
            organizationId: String,
            createTime: OffsetDateTime,
        ): DataStoreResult<Unit>

        /**
         * Creates an app draft's listing text.
         *
         * @param appDraftId the ID of the app draft to create listing text for.
         * @param name the app's name.
         * @param shortDescription the app's short description.
         * @return a content-based ETag of the created listing text, [None] if the app draft already
         * has listing text, or [DataStoreError.ConsistencyViolation] if the app draft does not
         * exist.
         */
        abstract suspend fun createAppDraftListingText(
            appDraftId: String,
            name: AppName,
            shortDescription: AppShortDescription,
        ): DataStoreResult<Option<String>>

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
        abstract suspend fun createOrgWithOwner(
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
        abstract suspend fun createSession(
            idHash: Sha256Hash,
            userId: String,
            createTime: OffsetDateTime,
            expireTime: OffsetDateTime,
        ): DataStoreResult<Unit>

        /**
         * Deletes a login session if the session is active.
         *
         * @param idHash the SHA-256 hash of the ID of the session to delete.
         * @param currentTime the current timestamp to be used for checking session expiration.
         * @return whether an active session was deleted.
         */
        abstract suspend fun deleteActiveSession(
            idHash: Sha256Hash,
            currentTime: OffsetDateTime,
        ): DataStoreResult<Boolean>

        /**
         * Deletes an app draft.
         *
         * @param appDraftId the ID of the app draft to delete.
         * @return [DataStoreError.EntityNotFound] if no app draft with the given ID exists.
         */
        abstract suspend fun deleteAppDraft(appDraftId: String): DataStoreResult<Unit>

        /**
         * Finds the API view of an app draft's listing text.
         *
         * @param appDraftId the ID of the app draft to find the listing text of.
         * @return the API view of the app draft's listing text, or [None] if no app draft with the
         * given ID exists or the app draft has no listing text.
         */
        abstract suspend fun findAppDraftListingTextApiViewByAppDraftId(
            appDraftId: String,
        ): DataStoreResult<Option<AppDraftListingTextApiView>>

        /**
         * Finds the API view of an organization.
         *
         * @param organizationId the ID of the organization to find.
         * @return the API view of the organization, or [None] if no organization with the given ID
         * exists.
         */
        abstract suspend fun findOrgApiViewById(
            organizationId: String,
        ): DataStoreResult<Option<OrgApiView>>

        /**
         * Finds the ID of the organization a given user owns.
         *
         * @param userId the ID of the user to find the organization of.
         * @return the ID of the organization the user owns, or [None] if no user with the given ID
         * exists.
         */
        abstract suspend fun findOrgIdByOwnerUserId(
            userId: String,
        ): DataStoreResult<Option<String>>

        /**
         * Finds the ID of a user a session belongs to if the session is active.
         *
         * @param sessionIdHash the SHA-256 hash of the session ID.
         * @param currentTime the current timestamp to be used for checking session expiration.
         * @return the ID of the user the session belongs to, or [None] if no such session exists or
         * the session is expired.
         */
        abstract suspend fun findUserIdByActiveSessionIdHash(
            sessionIdHash: Sha256Hash,
            currentTime: OffsetDateTime,
        ): DataStoreResult<Option<String>>

        /**
         * Finds the ID of a user with a given external user identity.
         *
         * @param externalUserId the external user ID of the user to find the ID of.
         * @return the ID of the user with the given external user identity, or [None] if none
         * exists.
         */
        abstract suspend fun findUserIdByExternalUserId(
            externalUserId: ExternalUserId,
        ): DataStoreResult<Option<String>>

        /**
         * Checks whether a subject has a given permission on a given resource.
         *
         * Authorization is derived from the relationships between application resources.
         *
         * @param request the permission to check.
         * @return whether the permission is granted.
         */
        abstract suspend fun hasPermission(request: HasPermissionRequest): DataStoreResult<Boolean>

        /**
         * Lists the API views of the app draft summaries in an organization.
         *
         * App draft summaries are listed from newest to oldest as defined by
         * [AppDraftSummarySortKey]. Exactly [maxResults] app draft summaries are returned if at
         * least that many match.
         *
         * @param organizationId the ID of the organization to list the app draft summaries of.
         * @param maxResults the maximum number of app draft summaries to return.
         * @param after the sort key after which to return app draft summaries, or [None] to start
         * with the first result.
         * @return the matching app draft summaries.
         */
        abstract suspend fun listAppDraftSummaryApiViewsInOrg(
            organizationId: String,
            maxResults: PositiveInt,
            after: Option<AppDraftSummarySortKey>,
        ): DataStoreResult<List<AppDraftSummaryApiView>>

        /**
         * Updates an app draft's listing text.
         *
         * @param appDraftId the ID of the app draft to update the listing text of.
         * @param etag the expected content-based ETag of the listing text before the update.
         * @param name the app's new name, or [None] to leave it unchanged.
         * @param shortDescription the app's new short description, or [None] to leave it
         * unchanged.
         * @return a content-based ETag of the updated listing text, or [None] if the app draft
         * doesn't exist or has no listing text with an ETag of [etag].
         */
        abstract suspend fun updateAppDraftListingText(
            appDraftId: String,
            etag: String,
            name: Option<AppName>,
            shortDescription: Option<AppShortDescription>,
        ): DataStoreResult<Option<String>>

        /**
         * Finds the API view of an organization.
         *
         * @param organizationId the ID of the organization to find.
         * @return the API view of the organization, or [DataStoreError.EntityNotFound] if no
         * organization with the given ID exists.
         */
        suspend fun requireOrgApiViewById(organizationId: String): DataStoreResult<OrgApiView> {
            return findOrgApiViewById(organizationId)
                .flatMap { it.toEither { DataStoreError.EntityNotFound() } }
        }

        /**
         * Finds the ID of the organization a given user owns.
         *
         * @param userId the ID of the user to find the organization of.
         * @return the ID of the organization the user owns, or [DataStoreError.EntityNotFound]
         * if no user with the given ID exists.
         */
        suspend fun requireOrgIdByOwnerUserId(userId: String): DataStoreResult<String> {
            return findOrgIdByOwnerUserId(userId)
                .flatMap { it.toEither { DataStoreError.EntityNotFound() } }
        }
    }
}
