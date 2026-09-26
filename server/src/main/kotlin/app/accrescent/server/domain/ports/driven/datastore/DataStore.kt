// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.PositiveLong
import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.android.ApplicationId
import app.accrescent.server.domain.android.NameAttribute
import app.accrescent.server.domain.android.SdkVersion
import app.accrescent.server.domain.appstore.ListingLanguage
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.raise.Raise
import arrow.core.raise.either
import java.time.OffsetDateTime

sealed class DataStoreError {
    data object ConsistencyViolation : DataStoreError()

    data object EntityNotFound : DataStoreError()
    data object IllegalState : DataStoreError()
    data class RollbackErrorOnCommit(
        val rollbackError: DataStoreError,
        val commitError: DataStoreError,
    ) : DataStoreError()

    data object SerializationFailure : DataStoreError()

    data object Unknown : DataStoreError()
}

typealias DataStoreResult<T> = Either<DataStoreError, T>

/**
 * A data store for application data.
 *
 * Implementations are typically backed by a transactional RDBMS.
 */
abstract class DataStore(private val randomSource: RandomSource) {
    private companion object {
        private const val BASE_TX_RETRY_DELAY_MILLIS = 25L
        private const val MAX_TX_RETRIES = 5
    }

    /**
     * Applies all the data store's pending migrations.
     *
     * This method is idempotent and will return successfully if the data store is
     * known to have no remaining pending migrations. Concurrent calls for the same backing data
     * store will block until migration is complete.
     */
    abstract fun migrateToHead(): DataStoreResult<Unit>

    /**
     * Runs the given lambda in the context of a transaction.
     *
     * All methods accessible through the [Transaction] provided in [block] participate in the
     * transaction if they are called within [block]'s scope. Calling any such methods outside of
     * [block]'s scope results in undefined behavior.
     *
     * The transaction is committed if [block] raises an [Either.Right]. If [block] returns an
     * [Either.Left] or throws, the transaction is rolled back.
     *
     * The transaction's isolation level is equivalent to the PL-3 isolation level defined in
     * [Generalized Isolation Level Definitions](https://doi.org/10.1109/ICDE.2000.839388) by Adya
     * et al. Less formally, it provides true serializability, which is distinct from and stronger
     * than some database implementations of the SQL standard SERIALIZABLE isolation level in that
     * it guarantees that concurrent execution of a set of serializable transactions will produce
     * the same effect as running them one at a time in some order. Snapshot isolation on its own,
     * notably, does not meet this standard.
     *
     * @param block a function to run in the context of the data store transaction.
     * @return the value returned by [block] wrapped in [Either.Right], or the mapped error wrapped
     * in [Either.Left].
     */
    protected abstract fun <T, E> runInTransaction(
        block: Raise<E>.(Transaction) -> T,
    ): DataStoreResult<Either<E, T>>

    /**
     * Runs the given lambda in the context of a transaction, retrying up to 5 times total on
     * [DataStoreError.SerializationFailure].
     *
     * See [runInTransaction] for the transaction semantics applied to each attempt.
     *
     * @param block a function to run in the context of the data store transaction.
     * @return the value returned by [block] on success, or [DataStoreError.SerializationFailure]
     * if all attempts are exhausted, or another [DataStoreError] if a non-retryable error occurs.
     */
    fun <T, E> runTxWithRetry(
        block: Raise<E>.(Transaction) -> T,
    ): DataStoreResult<Either<E, T>> = either {
        repeat(MAX_TX_RETRIES) { attempt ->
            val result = runInTransaction(block)
            if (result !is Either.Left || result.value !is DataStoreError.SerializationFailure) {
                return result
            }
            val delayMillis = Math
                // All of these operations are guaranteed to not throw since, given:
                //
                // - BASE_TX_RETRY_DELAY_MILLIS = 25
                // - max(attempt) = 4
                //
                // then BASE_TX_RETRY_DELAY_MILLIS * 2^(max(attempt)) = 400, which does not overflow
                // a Long.
                .multiplyExact(BASE_TX_RETRY_DELAY_MILLIS, Math.powExact(2, attempt))
                .let(PositiveLong::new)
                .unwrap()
                .let(randomSource::randomNonNegativeLong)
                .bindMapLeft { DataStoreError.IllegalState }
            Thread.sleep(delayMillis.value)
        }
        return runInTransaction(block)
    }

    interface Transaction {
        val appDrafts: AppDraftRepository
        val appPackages: AppPackageRepository
        val apps: AppRepository
        val authz: AuthorizationRepository
        val externalBlobs: ExternalBlobRepository
        val organizations: OrganizationRepository
        val sessions: SessionRepository
        val users: UserRepository
    }

    abstract class AppDraftRepository {
        /**
         * Marks a pending app draft upload as completed with an error.
         *
         * In the process, unlinks this upload's blob and marks it for deletion.
         *
         * @param pendingUploadId the ID of the pending app draft upload to complete.
         * @param error the error that occurred while processing the upload.
         * @param blobDeleteTime the time at which the released blob is marked as deleted.
         * @return [DataStoreError.EntityNotFound] if no incomplete upload exists with the given ID.
         */
        abstract fun completePendingUpload(
            pendingUploadId: String,
            error: AppDraftUploadProcessingError,
            blobDeleteTime: OffsetDateTime,
        ): DataStoreResult<Unit>

        /**
         * Counts the active app drafts in a given organization.
         *
         * An app draft is always considered active.
         *
         * @param organizationId the organization to count active app drafts in.
         * @return the number of active app drafts for the given organization.
         */
        abstract fun countActiveInOrganization(organizationId: String): DataStoreResult<ULong>

        /**
         * Creates a new, empty app draft.
         *
         * @param organizationId the ID of the organization to create this app draft in.
         * @param appDraftId the ID of app draft to create.
         * @param createTime the creation timestamp to set for the new app draft.
         * @return [DataStoreError.ConsistencyViolation] if the organization does not exist or an
         * app draft with the provided ID already exists.
         */
        abstract fun create(
            organizationId: String,
            appDraftId: String,
            createTime: OffsetDateTime,
        ): DataStoreResult<Unit>

        /**
         * Creates a new app draft listing.
         *
         * @param id the ID of the app draft listing to create.
         * @param appDraftId the ID of the app draft to create this listing for.
         * @param language the language the new listing's contents are written in.
         * @param name the name to set for the new listing.
         * @param shortDescription the short description to set for the new listing.
         * @return [DataStoreError.ConsistencyViolation] if a listing with the same ID or
         * (appDraftId, language) pair already exists or the referenced app draft does not exist.
         */
        abstract fun createListing(
            id: String,
            appDraftId: String,
            language: ListingLanguage,
            name: String,
            shortDescription: String,
        ): DataStoreResult<Unit>

        /**
         * Deletes an app draft along with its associated data.
         *
         * Because they are part of the app draft, this draft's listings, package, and pending
         * upload are deleted as well. All blobs owned by these entities are marked as deleted.
         *
         * @param id the ID of the app draft to delete.
         * @param blobDeleteTime the time at which the released blobs are marked as deleted.
         * @return [DataStoreError.EntityNotFound] if the app draft does not exist.
         */
        abstract fun deleteById(id: String, blobDeleteTime: OffsetDateTime): DataStoreResult<Unit>

        /**
         * Deletes an app draft listing.
         *
         * Because it is part of the listing, also deletes this listing's pending icon upload if it
         * exists, marking its blob for deletion.
         *
         * @param id the ID of the app draft listing to delete.
         * @param blobDeleteTime the time at which the released blob is marked as deleted.
         * @return [DataStoreError.EntityNotFound] if the listing does not exist.
         */
        abstract fun deleteListingById(
            id: String,
            blobDeleteTime: OffsetDateTime,
        ): DataStoreResult<Unit>

        /**
         * Deletes a pending app draft listing icon upload for a given app draft listing.
         *
         * In the process, marks the blob owned by the upload for deletion.
         *
         * @param appDraftListingId the app draft listing ID whose pending icon upload should be
         * deleted.
         * @param blobDeleteTime the time at which the released blob is marked as deleted.
         * @return [DataStoreError.EntityNotFound] if no pending icon upload exists for the given app
         * draft listing, including if no app draft listing exists with the given ID.
         */
        abstract fun deletePendingListingIconUploadByListingId(
            appDraftListingId: String,
            blobDeleteTime: OffsetDateTime,
        ): DataStoreResult<Unit>

        /**
         * Deletes a pending app draft upload for a given app draft.
         *
         * In the process, marks the blob owned by the upload for deletion.
         *
         * @param appDraftId the app draft ID whose pending upload should be deleted.
         * @param blobDeleteTime the time at which the released blob is marked as deleted.
         * @return [DataStoreError.EntityNotFound] if no pending upload exists for the given app
         * draft, including if no app draft exists with the given ID.
         */
        abstract fun deletePendingUploadByAppDraftId(
            appDraftId: String,
            blobDeleteTime: OffsetDateTime,
        ): DataStoreResult<Unit>

        /**
         * Determines whether a submitted app draft exists with an app package whose app ID
         * matches the given app ID.
         *
         * @param appId the app ID to check the existence of a submitted app draft for.
         * @return whether a submitted app draft with an app package matching the given app ID
         * exists.
         */
        abstract fun existsSubmittedForAppId(appId: ApplicationId): DataStoreResult<Boolean>

        /**
         * Finds the API view of an existing app draft.
         *
         * @param id the ID of the app draft to find.
         * @return the API view of the app draft with the given ID, or [None] if it doesn't exist.
         */
        abstract fun findApiViewById(id: String): DataStoreResult<Option<AppDraftApiView>>

        /**
         * Finds the API views for a list of app drafts in a given organization which the given user
         * is authorized to view.
         *
         * @param organizationId the organization to list app drafts from.
         * @param userId the user ID to use for authorization.
         * @param afterAppDraftId the app draft ID to start listing after, or [None] to start from
         * the beginning.
         * @return the list of app draft API views matching the query.
         */
        abstract fun findApiViewsForOrganizationAndUserByQuery(
            organizationId: String,
            userId: String,
            maxResults: NonNegativeInt,
            afterAppDraftId: Option<String>,
        ): DataStoreResult<List<AppDraftApiView>>

        /**
         * Finds the API view of an existing app draft listing.
         *
         * @param id the ID of the app draft listing to find.
         * @return the API view of the app draft listing with the given ID, or [None] if it doesn't
         * exist.
         */
        abstract fun findListingApiViewById(
            id: String,
        ): DataStoreResult<Option<AppDraftListingApiView>>

        /**
         * Finds the API views for a list of app draft listings for a given app draft which a given
         * user is authorized to view.
         *
         * Results are ordered alphabetically by the listing language's BCP-47 code.
         *
         * @param appDraftId the app draft to list listings for.
         * @param userId the user ID to use for authorization.
         * @param maxResults the maximum number of app draft listings to retrieve.
         * @param afterLanguage the listing language to start listing after, or [None] to start
         * from the beginning.
         * @return the list of app draft listing API views matching the query.
         */
        abstract fun findListingApiViewsForAppDraftAndUserByQuery(
            appDraftId: String,
            userId: String,
            maxResults: NonNegativeInt,
            afterLanguage: Option<ListingLanguage>,
        ): DataStoreResult<List<AppDraftListingApiView>>

        /**
         * Finds a pending app draft listing icon upload by its target blob's object key.
         *
         * @param objectKey the object key of the blob the pending icon upload targets.
         * @return the pending app draft listing icon upload with the given object key, or [None] if
         * it doesn't exist.
         */
        abstract fun findPendingListingIconUploadByObjectKey(
            objectKey: String,
        ): DataStoreResult<Option<PendingAppDraftListingIconUpload>>

        /**
         * Finds a pending app draft upload by its target blob's object key.
         *
         * @param objectKey the object key of the blob the pending app draft upload targets.
         * @return the pending app draft upload with the given object key, or [None] if it doesn't
         * exist.
         */
        abstract fun findPendingUploadByObjectKey(
            objectKey: String,
        ): DataStoreResult<Option<PendingAppDraftUpload>>

        /**
         * Determines whether an app draft has a default listing assigned.
         *
         * @param id the ID of the app draft to check default listing presence for.
         * @return [DataStoreError.EntityNotFound] if the app draft does not exist, otherwise
         * whether the app draft has a default listing assigned.
         */
        abstract fun hasDefaultListing(id: String): DataStoreResult<Boolean>

        /**
         * Determines whether an app draft is submitted.
         *
         * @param id the ID of the app draft to check the submission status of.
         * @return [DataStoreError.EntityNotFound] if the app draft does not exist, otherwise
         * whether the app draft is submitted.
         */
        abstract fun isSubmitted(id: String): DataStoreResult<Boolean>

        /**
         * Determines whether an app draft listing exists.
         *
         * @param listingId the ID of the app draft listing to check the existence of.
         * @param appDraftId the app draft ID expected of the listing to check the existence of.
         * @return true if an app draft listing with the given ID and app draft ID exists, false
         * otherwise.
         */
        abstract fun listingExistsByIdForAppDraft(
            listingId: String,
            appDraftId: String,
        ): DataStoreResult<Boolean>

        /**
         * Determines whether an app draft listing exists for a given app draft and language.
         *
         * @param appDraftId the app draft ID to check the existence of a listing for.
         * @param language the language to check the existence of a listing for.
         * @return whether an app draft listing with the given app draft ID and language exists.
         */
        abstract fun listingExistsByLanguageForAppDraft(
            appDraftId: String,
            language: ListingLanguage,
        ): DataStoreResult<Boolean>

        /**
         * Determines whether an app draft listing is the default listing of its app draft.
         *
         * @param listingId the ID of the app draft listing to check the default status of.
         * @return true if an app draft listing with the given ID exists and is its app draft's
         * default listing, false otherwise.
         */
        abstract fun listingIsDefault(listingId: String): DataStoreResult<Boolean>

        /**
         * Determines whether a pending app draft listing icon upload exists for a given app draft
         * listing.
         *
         * @param appDraftListingId the app draft listing ID to check the existence of a pending icon
         * upload for.
         * @return whether a pending app draft listing icon upload for the given app draft listing ID
         * exists.
         */
        abstract fun pendingListingIconUploadExistsByListingId(
            appDraftListingId: String,
        ): DataStoreResult<Boolean>

        /**
         * Determines whether a pending app draft upload exists for a given app draft.
         *
         * @param appDraftId the app draft ID to check the existence of a pending upload for.
         * @return whether a pending app draft upload for the given app draft ID exists.
         */
        abstract fun pendingUploadExistsByAppDraftId(appDraftId: String): DataStoreResult<Boolean>

        /**
         * Finds the API view of an existing app draft.
         *
         * @param id the ID of the app draft to find.
         * @return the API view of the app draft with the given ID, or
         * [DataStoreError.EntityNotFound] if it doesn't exist.
         */
        fun requireApiViewById(id: String): DataStoreResult<AppDraftApiView> {
            return findApiViewById(id).flatMap { it.toEither { DataStoreError.EntityNotFound } }
        }

        /**
         * Finds the API view of an existing app draft listing.
         *
         * @param id the ID of the app draft listing to find.
         * @return the API view of the app draft listing with the given ID, or
         * [DataStoreError.EntityNotFound] if it doesn't exist.
         */
        fun requireListingApiViewById(id: String): DataStoreResult<AppDraftListingApiView> {
            return findListingApiViewById(id)
                .flatMap { it.toEither { DataStoreError.EntityNotFound } }
        }

        /**
         * Saves a new pending app draft listing icon upload along with the blob it owns.
         *
         * @param upload the pending app draft listing icon upload to save.
         * @param blob the pending external blob the upload owns.
         * @return [DataStoreError.ConsistencyViolation] if a pending icon upload for the same
         * app draft listing or the same object key already exists, a blob already exists for the
         * blob's ([ExternalBlob.bucketName], [ExternalBlob.objectKey]) pair, the app draft listing
         * does not exist, or the blob's ID does not match the upload's external blob ID.
         */
        abstract fun saveListingIconUpload(
            upload: PendingAppDraftListingIconUpload.Incomplete,
            blob: ExternalBlob<ExternalBlob.Status.Pending>,
        ): DataStoreResult<Unit>

        /**
         * Saves a new pending app draft upload along with the pending external blob it owns.
         *
         * @param upload the pending app draft upload to save.
         * @param blob the pending external blob the upload owns.
         * @return [DataStoreError.ConsistencyViolation] if a pending upload for the same app
         * draft or the same object key already exists, a blob already exists for the blob's
         * ([ExternalBlob.bucketName], [ExternalBlob.objectKey]) pair, the app draft does not
         * exist, or the blob's ID does not match the upload's external blob ID.
         */
        abstract fun saveUpload(
            upload: PendingAppDraftUpload.Incomplete,
            blob: ExternalBlob<ExternalBlob.Status.Pending>,
        ): DataStoreResult<Unit>

        /**
         * Updates the default listing of an app draft.
         *
         * @param appDraftId the ID of the app draft to update.
         * @param defaultAppDraftListingId the ID of app draft listing to set as the default, or
         * [None] to unset the app draft's default listing.
         * @return [DataStoreError.EntityNotFound] if the app draft does not exist, or
         * [DataStoreError.ConsistencyViolation] if an app draft listing with the given ID
         * does not exist or if the referenced app draft listing is not associated with the given
         * app draft.
         */
        abstract fun updateDefaultListing(
            appDraftId: String,
            defaultAppDraftListingId: Option<String>,
        ): DataStoreResult<Unit>

        /**
         * Updates the fields of an app draft listing.
         *
         * @param listingId the ID of the app draft listing to update.
         * @param name the new name for the listing, or [None] to leave unchanged.
         * @param shortDescription the new short description for the listing, or [None] to leave
         * unchanged.
         * @return [DataStoreError.EntityNotFound] if the listing does not exist.
         */
        abstract fun updateListing(
            listingId: String,
            name: Option<String>,
            shortDescription: Option<String>,
        ): DataStoreResult<Unit>

        /**
         * Updates the submit time of an app draft.
         *
         * @param appDraftId the ID of the app draft to update.
         * @param submitTime the submit time to set for the app draft.
         * @return [DataStoreError.EntityNotFound] if the app draft does not exist, or
         * [DataStoreError.ConsistencyViolation] if the app draft doesn't have an associated
         * app package and default listing.
         */
        abstract fun updateSubmitTime(
            appDraftId: String,
            submitTime: OffsetDateTime,
        ): DataStoreResult<Unit>
    }

    abstract class AppPackageRepository {
        /**
         * Finds the app ID of an app package by the ID of its corresponding app draft.
         *
         * @param appDraftId the app draft to find a package's app ID for.
         * @return the app ID of the app package for the app draft with the given ID, or [None] if
         * no such package exists.
         */
        abstract fun findAppIdByAppDraftId(
            appDraftId: String,
        ): DataStoreResult<Option<ApplicationId>>

        /**
         * Finds an app package by the ID of its corresponding app draft.
         *
         * @param appDraftId the app draft to find a package for.
         * @return the app package for the app draft with the given ID, or [None] if one does not
         * exist.
         */
        abstract fun findByAppDraftId(appDraftId: String): DataStoreResult<Option<AppPackage>>

        /**
         * Saves a new app package by completing a pending app draft upload.
         *
         * In the process, commits the upload's pending blob, moves the blob's ownership to the new
         * package, associates the app draft with the new package, marks the upload as succeeded,
         * and deletes the existing app draft's package if one exists, marking said package's blob
         * for deletion.
         *
         * @param pendingUploadId the ID of the incomplete pending app draft upload to commit.
         * @param appPackage the app package to save.
         * @param permissions the permissions the app package requests, each mapped to its max SDK
         * version.
         * @param blobVersion the version assigned to the blob by the blob storage service.
         * @param replacedBlobDeleteTime the time at which a replaced package's blob is marked as
         * deleted.
         * @return [DataStoreError.ConsistencyViolation] if a package with the same ID already
         * exists, the app draft does not exist, no incomplete upload exists with the given ID, the
         * package does not describe that upload's blob and app draft, or the provided version type
         * doesn't match the blob's service.
         */
        abstract fun saveFromPendingUpload(
            pendingUploadId: String,
            appPackage: AppPackage,
            permissions: Map<NameAttribute, Option<SdkVersion>>,
            blobVersion: ExternalBlob.BlobVersion,
            replacedBlobDeleteTime: OffsetDateTime,
        ): DataStoreResult<Unit>
    }

    abstract class AppRepository {
        /**
         * Counts the apps in a given app draft's organization.
         *
         * @param appDraftId the ID of the app draft whose organization apps should be counted in.
         * @return the number of apps for the app draft's organization.
         */
        abstract fun countInAppDraftOrganization(appDraftId: String): DataStoreResult<ULong>

        /**
         * Finds an existing app.
         *
         * @param id the ID of the app to find.
         * @return the app with the given ID, or [None] if it doesn't exist.
         */
        abstract fun findById(id: String): DataStoreResult<Option<App>>

        /**
         * Finds an existing app.
         *
         * @param id the ID of the app to find.
         * @return the app with the given ID, or [DataStoreError.EntityNotFound] if it doesn't
         * exist.
         */
        fun requireById(id: String): DataStoreResult<App> {
            return findById(id).flatMap { it.toEither { DataStoreError.EntityNotFound } }
        }

        /**
         * Saves a new app along with its default listing.
         *
         * @param app the app to save.
         * @param defaultListing the listing to save as this app's default.
         * @return [DataStoreError.ConsistencyViolation] if an app with the same ID already
         * exists, the organization does not exist, the app's default app listing ID doesn't match
         * the app listing's ID, or the app listing's app ID doesn't match the app's ID.
         */
        abstract fun saveWithDefaultListing(
            app: App,
            defaultListing: AppListing,
        ): DataStoreResult<Unit>

        /**
         * Updates the publicly listed status of an app.
         *
         * @param appId the ID of the app to update.
         * @param publiclyListed whether the app should be publicly listed.
         * @return [DataStoreError.EntityNotFound] if the app does not exist.
         */
        abstract fun updatePubliclyListed(
            appId: String,
            publiclyListed: Boolean,
        ): DataStoreResult<Unit>
    }

    /**
     * A ReBAC-based authorization repository for application operations.
     *
     * Authorization repositories read relationships between application resources and determine
     * authorization results for application operations based on these relationships. This
     * authorization model is called relationship-based access control, or ReBAC.
     *
     * Because the authorization repository is tied to an associated [DataStore], it participates
     * in [DataStore.Transaction]s, eliminating the
     * [dual-write problem](https://authzed.com/blog/the-dual-write-problem) which would otherwise
     * need to be solved if using an external data store for resource relationships.
     */
    abstract class AuthorizationRepository {
        /**
         * Checks whether a subject has a given permission on a given resource.
         *
         * @return whether the permission is granted.
         */
        abstract fun hasPermission(request: HasPermissionRequest): DataStoreResult<Boolean>
    }

    abstract class ExternalBlobRepository {
        /**
         * Finds an existing external blob.
         *
         * @param id the ID of the external blob to find.
         * @return the blob with the given ID, or [None] if it doesn't exist.
         */
        abstract fun findById(id: String): DataStoreResult<Option<ExternalBlob<*>>>

        /**
         * Finds an existing external blob.
         *
         * @param id the ID of the external blob to find.
         * @return the blob with the given ID, or [DataStoreError.EntityNotFound] if it doesn't
         * exist.
         */
        fun requireById(id: String): DataStoreResult<ExternalBlob<*>> {
            return findById(id).flatMap { it.toEither { DataStoreError.EntityNotFound } }
        }

        /**
         * Finds an existing, committed external blob.
         *
         * @param id the ID of the external blob to find.
         * @return the committed blob with the given ID, or [DataStoreError.EntityNotFound] if it
         * doesn't exist or is not committed.
         */
        fun requireCommittedById(
            id: String,
        ): DataStoreResult<ExternalBlob<ExternalBlob.Status.Committed<*>>> = either {
            when (val blob = requireById(id).bind()) {
                is ExternalBlob.Local -> when (val status = blob.status) {
                    is ExternalBlob.Status.Committed -> ExternalBlob.Local(
                        id = blob.id,
                        createTime = blob.createTime,
                        bucketName = blob.bucketName,
                        objectKey = blob.objectKey,
                        status = status,
                    )

                    ExternalBlob.Status.Pending,
                    is ExternalBlob.Status.Deleted -> raise(DataStoreError.EntityNotFound)
                }

                is ExternalBlob.Gcs -> when (val status = blob.status) {
                    is ExternalBlob.Status.Committed -> ExternalBlob.Gcs(
                        id = blob.id,
                        createTime = blob.createTime,
                        bucketName = blob.bucketName,
                        objectKey = blob.objectKey,
                        status = status,
                    )

                    ExternalBlob.Status.Pending,
                    is ExternalBlob.Status.Deleted -> raise(DataStoreError.EntityNotFound)
                }
            }
        }
    }

    abstract class OrganizationRepository {
        /**
         * Finds the ID of the organization a given user owns.
         *
         * @param userId the ID of the user to find the organization of.
         * @return the ID of the organization the user owns, or [None] if no user with the given ID
         * exists.
         */
        abstract fun findIdByOwnerUserId(userId: String): DataStoreResult<Option<String>>

        /**
         * Finds the ID of the organization a given user owns.
         *
         * @param userId the ID of the user to find the organization of.
         * @return the ID of the organization the user owns, or [DataStoreError.EntityNotFound] if
         * no user with the given ID exists.
         */
        fun requireIdByOwnerUserId(userId: String): DataStoreResult<String> {
            return findIdByOwnerUserId(userId).flatMap { it.toEither { DataStoreError.EntityNotFound } }
        }

        /**
         * Saves a new organization along with its owning user.
         *
         * @param organizationId the ID of the organization to save.
         * @param userId the ID of the new user to save who will own the organization represented by
         * [organizationId].
         * @param externalUserId the external identity of the user.
         * @param createTime the creation timestamp to set for the new organization and user.
         *
         * @return [DataStoreError.ConsistencyViolation] if an organization or user with the
         * respective provided ID already exists or if a user with the external ID already exists.
         */
        abstract fun saveWithOwner(
            organizationId: String,
            userId: String,
            externalUserId: ExternalUserId,
            createTime: OffsetDateTime,
        ): DataStoreResult<Unit>
    }

    abstract class SessionRepository {
        /**
         * Creates a new session for a given user.
         *
         * @param idHash the SHA-256 hash of the session ID to store.
         * @param userId the user to associate the session with.
         * @param createTime the creation timestamp to set for the new session.
         * @param expireTime the expiration timestamp to set for the new session after which it will
         * no longer be valid.
         * @return [DataStoreError.ConsistencyViolation] if a session with the same ID hash already
         * exists, the user does not exist, or [expireTime] is not later than [createTime].
         */
        abstract fun create(
            idHash: Sha256Hash,
            userId: String,
            createTime: OffsetDateTime,
            expireTime: OffsetDateTime,
        ): DataStoreResult<Unit>
    }

    abstract class UserRepository {
        /**
         * Finds the ID of a user with a given external identity.
         *
         * @param externalUserId the external user ID of the user to find the ID of.
         * @return the ID of the user with the given external identity, or [None] if none exists.
         */
        abstract fun findIdByExternalUserId(
            externalUserId: ExternalUserId,
        ): DataStoreResult<Option<String>>

        /**
         * Finds the ID of a user an active session belongs to.
         *
         * @param sessionIdHash the SHA-256 hash of the session ID.
         * @param currentTime the current timestamp to be used for checking session expiration.
         * @return the ID of the user the session belongs to, or [None] if no such session exists or
         * the session is expired.
         */
        abstract fun findIdBySessionIdHash(
            sessionIdHash: Sha256Hash,
            currentTime: OffsetDateTime,
        ): DataStoreResult<Option<String>>
    }
}
