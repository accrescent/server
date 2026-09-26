// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console

import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.IdType
import app.accrescent.server.domain.appstore.ListingLanguage
import app.accrescent.server.domain.ports.driven.blobstorage.BlobId
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorage
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorageBackend
import app.accrescent.server.domain.ports.driven.blobstorage.UploadType
import app.accrescent.server.domain.ports.driven.datastore.AppDraftApiView
import app.accrescent.server.domain.ports.driven.datastore.AppPackageApiView
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.datastore.ExternalBlob
import app.accrescent.server.domain.ports.driven.datastore.HasPermissionRequest
import app.accrescent.server.domain.ports.driven.datastore.PendingAppDraftListingIconUpload
import app.accrescent.server.domain.ports.driven.datastore.PendingAppDraftUpload
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.console.ActiveAppDraftLimitExceededError
import app.accrescent.server.domain.ports.driving.api.console.AppDraftApi
import app.accrescent.server.domain.ports.driving.api.console.AppDraftHasNoDefaultListingError
import app.accrescent.server.domain.ports.driving.api.console.AppDraftHasNoPackageError
import app.accrescent.server.domain.ports.driving.api.console.AppDraftListingAlreadyExistsError
import app.accrescent.server.domain.ports.driving.api.console.AppDraftListingNotFoundError
import app.accrescent.server.domain.ports.driving.api.console.AppDraftPackageNotFoundError
import app.accrescent.server.domain.ports.driving.api.console.AppDraftSubmittedError
import app.accrescent.server.domain.ports.driving.api.console.AppDraftSubmittedForAppIdError
import app.accrescent.server.domain.ports.driving.api.console.CallContext
import app.accrescent.server.domain.ports.driving.api.console.CreateAppDraftError
import app.accrescent.server.domain.ports.driving.api.console.CreateAppDraftListingError
import app.accrescent.server.domain.ports.driving.api.console.CreateAppDraftListingRequest
import app.accrescent.server.domain.ports.driving.api.console.CreateAppDraftListingResponse
import app.accrescent.server.domain.ports.driving.api.console.CreateAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console.CreateAppDraftResponse
import app.accrescent.server.domain.ports.driving.api.console.DeleteAppDraftError
import app.accrescent.server.domain.ports.driving.api.console.DeleteAppDraftListingError
import app.accrescent.server.domain.ports.driving.api.console.DeleteAppDraftListingRequest
import app.accrescent.server.domain.ports.driving.api.console.DeleteAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console.DownloadAppDraftError
import app.accrescent.server.domain.ports.driving.api.console.DownloadAppDraftListingIconError
import app.accrescent.server.domain.ports.driving.api.console.DownloadAppDraftListingIconRequest
import app.accrescent.server.domain.ports.driving.api.console.DownloadAppDraftListingIconResponse
import app.accrescent.server.domain.ports.driving.api.console.DownloadAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console.DownloadAppDraftResponse
import app.accrescent.server.domain.ports.driving.api.console.GetAppDraftError
import app.accrescent.server.domain.ports.driving.api.console.GetAppDraftListingError
import app.accrescent.server.domain.ports.driving.api.console.GetAppDraftListingRequest
import app.accrescent.server.domain.ports.driving.api.console.GetAppDraftListingResponse
import app.accrescent.server.domain.ports.driving.api.console.GetAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console.GetAppDraftResponse
import app.accrescent.server.domain.ports.driving.api.console.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console.InvalidPageTokenError
import app.accrescent.server.domain.ports.driving.api.console.ListAppDraftListingsError
import app.accrescent.server.domain.ports.driving.api.console.ListAppDraftListingsRequest
import app.accrescent.server.domain.ports.driving.api.console.ListAppDraftListingsResponse
import app.accrescent.server.domain.ports.driving.api.console.ListAppDraftsError
import app.accrescent.server.domain.ports.driving.api.console.ListAppDraftsRequest
import app.accrescent.server.domain.ports.driving.api.console.ListAppDraftsResponse
import app.accrescent.server.domain.ports.driving.api.console.PublishAppDraftError
import app.accrescent.server.domain.ports.driving.api.console.PublishAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console.PublishAppDraftResponse
import app.accrescent.server.domain.ports.driving.api.console.PublishedAppLimitExceededError
import app.accrescent.server.domain.ports.driving.api.console.SubmitAppDraftError
import app.accrescent.server.domain.ports.driving.api.console.SubmitAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console.UpdateAppDraftError
import app.accrescent.server.domain.ports.driving.api.console.UpdateAppDraftListingError
import app.accrescent.server.domain.ports.driving.api.console.UpdateAppDraftListingRequest
import app.accrescent.server.domain.ports.driving.api.console.UpdateAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console.UploadAppDraftError
import app.accrescent.server.domain.ports.driving.api.console.UploadAppDraftListingIconError
import app.accrescent.server.domain.ports.driving.api.console.UploadAppDraftListingIconRequest
import app.accrescent.server.domain.ports.driving.api.console.UploadAppDraftListingIconResponse
import app.accrescent.server.domain.ports.driving.api.console.UploadAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console.UploadAppDraftResponse
import app.accrescent.server.domain.ports.driving.api.console.toServerError
import app.accrescent.server.impl.v1.ListAppDraftListingsPageToken
import app.accrescent.server.impl.v1.ListAppDraftsPageToken
import app.accrescent.server.impl.v1.listAppDraftListingsPageToken
import app.accrescent.server.impl.v1.listAppDraftsPageToken
import arrow.core.Either
import arrow.core.None
import arrow.core.Some
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.google.protobuf.InvalidProtocolBufferException
import kotlin.io.encoding.Base64
import app.accrescent.server.domain.ports.driving.api.console.AppDraft as ApiAppDraft
import app.accrescent.server.domain.ports.driving.api.console.AppDraftListing as ApiAppDraftListing
import app.accrescent.server.domain.ports.driving.api.console.AppPackage as ApiAppPackage

private val ACTIVE_APP_DRAFT_LIMIT = 3uL
private val PUBLISHED_APP_LIMIT = 1uL

class AppDraftApiImpl(
    private val dataStore: DataStore,
    private val objectStorage: BlobStorage<BlobId>,
    randomSource: RandomSource,
    private val timestampSource: TimestampSource,
    private val appDraftUploadBucketName: String,
    private val appDraftListingIconUploadBucketName: String,
) : AppDraftApi {
    private val idGenerator = IdGenerator(randomSource)

    override fun createAppDraft(
        context: CallContext,
        request: CreateAppDraftRequest,
    ): Either<CreateAppDraftError, CreateAppDraftResponse> = either {
        val appDraftId = dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(HasPermissionRequest.CreateAppDraft(request.organizationId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            val activeAppDraftCount = tx.appDrafts
                .countActiveInOrganization(request.organizationId)
                .bindMapLeft(::toServerError)
            ensure(activeAppDraftCount < ACTIVE_APP_DRAFT_LIMIT) {
                ActiveAppDraftLimitExceededError(ACTIVE_APP_DRAFT_LIMIT)
            }

            val appDraftId = idGenerator.generateId(IdType.APP_DRAFT).bindMapLeft(::toServerError)
            tx.appDrafts
                .create(
                    organizationId = request.organizationId,
                    appDraftId = appDraftId,
                    createTime = timestampSource.now(),
                )
                .bindMapLeft(::toServerError)
            appDraftId
        }
            .bindMapLeft(::toServerError)
            .bind()

        CreateAppDraftResponse(appDraftId)
    }

    override fun getAppDraft(
        context: CallContext,
        request: GetAppDraftRequest,
    ): Either<GetAppDraftError, GetAppDraftResponse> = either {
        val appDraft = dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(HasPermissionRequest.ViewAppDraft(request.appDraftId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft is guaranteed to exist since permission is granted
            tx.appDrafts.requireApiViewById(request.appDraftId).bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()
            .toApiResource()

        GetAppDraftResponse(appDraft)
    }

    override fun listAppDrafts(
        context: CallContext,
        request: ListAppDraftsRequest,
    ): Either<ListAppDraftsError, ListAppDraftsResponse> = either {
        // We never need a page size beyond Int.MAX_VALUE since we limit the number of app drafts
        // per organization well below that, so we can safely coerce the page size down for our
        // DataStore query without changing behavior
        val maxResults = request.pageSize
            .coerceAtMost(Int.MAX_VALUE.toUInt())
            .toInt()
            .let(NonNegativeInt::new)
            .unwrap()
        val lastAppDraftId = request.pageToken.map {
            try {
                val bytes = Base64.UrlSafe.decode(it)
                val token = ListAppDraftsPageToken.parseFrom(bytes)
                if (!token.hasLastAppDraftId()) {
                    raise(InvalidPageTokenError)
                }

                token.lastAppDraftId
            } catch (_: IllegalArgumentException) {
                raise(InvalidPageTokenError)
            } catch (_: InvalidProtocolBufferException) {
                raise(InvalidPageTokenError)
            }
        }

        val appDrafts = dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            tx.appDrafts
                .findApiViewsForOrganizationAndUserByQuery(
                    organizationId = request.organizationId,
                    userId = userId,
                    maxResults = maxResults,
                    afterAppDraftId = lastAppDraftId,
                )
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()
            .map { it.toApiResource() }
        val nextPageToken = if (appDrafts.isNotEmpty()) {
            val token = listAppDraftsPageToken { this.lastAppDraftId = appDrafts.last().id }
            Some(Base64.UrlSafe.encode(token.toByteArray()))
        } else {
            None
        }

        ListAppDraftsResponse(appDrafts, nextPageToken)
    }

    override fun uploadAppDraft(
        context: CallContext,
        request: UploadAppDraftRequest,
    ): Either<UploadAppDraftError, UploadAppDraftResponse> = either {
        dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(
                    HasPermissionRequest.ReplaceAppDraftPackage(request.appDraftId, userId)
                )
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft is guaranteed to exist since permission is granted
            val isSubmitted =
                tx.appDrafts.isSubmitted(request.appDraftId).bindMapLeft(::toServerError)
            ensure(!isSubmitted) { AppDraftSubmittedError(request.appDraftId) }

            // Generate a signed URI for upload
            val objectKey =
                idGenerator.generateId(IdType.BLOB_OBJECT_KEY).bindMapLeft(::toServerError)
            val blobLocation = BlobId.Location(appDraftUploadBucketName, objectKey)
            val (storageBackend, uploadUri) = objectStorage
                .signUploadUri(type = UploadType.APK_SET, location = blobLocation)
                .bindMapLeft(::toServerError)

            // Persist a pending reference to the blob we intend to create
            val now = timestampSource.now()
            val blobId = idGenerator.generateId(IdType.EXTERNAL_BLOB).bindMapLeft(::toServerError)
            val blob = when (storageBackend) {
                BlobStorageBackend.GCS -> ExternalBlob.Gcs(
                    id = blobId,
                    createTime = now,
                    bucketName = blobLocation.bucketName,
                    objectKey = blobLocation.objectKey,
                    status = ExternalBlob.Status.Pending,
                )

                BlobStorageBackend.LOCAL -> ExternalBlob.Local(
                    id = blobId,
                    createTime = now,
                    bucketName = blobLocation.bucketName,
                    objectKey = blobLocation.objectKey,
                    status = ExternalBlob.Status.Pending,
                )
            }
            // Upsert the app draft's pending upload
            val pendingUploadExists = tx.appDrafts
                .pendingUploadExistsByAppDraftId(request.appDraftId)
                .bindMapLeft(::toServerError)
            if (pendingUploadExists) {
                tx.appDrafts
                    .deletePendingUploadByAppDraftId(request.appDraftId, now)
                    .bindMapLeft(::toServerError)
            }
            tx.appDrafts
                .saveUpload(
                    PendingAppDraftUpload.Incomplete(
                        id = idGenerator
                            .generateId(IdType.PENDING_APP_DRAFT_UPLOAD)
                            .bindMapLeft(::toServerError),
                        appDraftId = request.appDraftId,
                        objectKey = objectKey,
                        createTime = now,
                        externalBlobId = blob.id,
                    ),
                    blob,
                )
                .bindMapLeft(::toServerError)

            UploadAppDraftResponse(uploadUri)
        }
            .bindMapLeft(::toServerError)
            .bind()
    }

    override fun downloadAppDraft(
        context: CallContext,
        request: DownloadAppDraftRequest
    ): Either<DownloadAppDraftError, DownloadAppDraftResponse> = either {
        val blob = dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(HasPermissionRequest.DownloadAppDraft(request.appDraftId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            tx.appPackages.findByAppDraftId(request.appDraftId)
                .bindMapLeft(::toServerError)
                .map {
                    tx.externalBlobs
                        .requireCommittedById(it.externalBlobId)
                        .bindMapLeft(::toServerError)
                }
        }
            .bindMapLeft(::toServerError)
            .bind()
            // The app draft with the ID used to find this package is guaranteed to exist since
            // permission was granted, so we know the package must be missing, not the app draft
            .toEitherBind { AppDraftPackageNotFoundError(request.appDraftId) }

        val uri = objectStorage
            .signDownloadUri(blob.toBlobId())
            .bindMapLeft(::toServerError)

        DownloadAppDraftResponse(uri)
    }

    override fun updateAppDraft(
        context: CallContext,
        request: UpdateAppDraftRequest
    ): Either<UpdateAppDraftError, Unit> = either {
        dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(HasPermissionRequest.UpdateAppDraft(request.appDraftId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft is guaranteed to exist since permission is granted
            val isSubmitted =
                tx.appDrafts.isSubmitted(request.appDraftId).bindMapLeft(::toServerError)
            ensure(!isSubmitted) { AppDraftSubmittedError(request.appDraftId) }

            val listingExists = tx.appDrafts
                .listingExistsByIdForAppDraft(request.defaultAppDraftListingId, request.appDraftId)
                .bindMapLeft(::toServerError)
            ensure(listingExists) { AppDraftListingNotFoundError(request.defaultAppDraftListingId) }

            tx.appDrafts
                .updateDefaultListing(request.appDraftId, Some(request.defaultAppDraftListingId))
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()
    }

    override fun submitAppDraft(
        context: CallContext,
        request: SubmitAppDraftRequest
    ): Either<SubmitAppDraftError, Unit> = either {
        dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(HasPermissionRequest.SubmitAppDraft(request.appDraftId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft is guaranteed to exist since permission is granted
            val isSubmitted =
                tx.appDrafts.isSubmitted(request.appDraftId).bindMapLeft(::toServerError)
            ensure(!isSubmitted) { AppDraftSubmittedError(request.appDraftId) }

            // The app draft is known to exist, so a missing app ID means a missing package
            val appId = tx.appPackages
                .findAppIdByAppDraftId(request.appDraftId)
                .bindMapLeft(::toServerError)
                .toEitherBind { AppDraftHasNoPackageError(request.appDraftId) }

            val hasDefaultListing = tx.appDrafts
                .hasDefaultListing(request.appDraftId)
                .bindMapLeft(::toServerError)
            ensure(hasDefaultListing) { AppDraftHasNoDefaultListingError(request.appDraftId) }

            val appDraftSubmittedForAppId = tx.appDrafts
                .existsSubmittedForAppId(appId)
                .bindMapLeft(::toServerError)
            ensure(!appDraftSubmittedForAppId) {
                AppDraftSubmittedForAppIdError(appId.value)
            }

            val publishedAppCount = tx.apps
                .countInAppDraftOrganization(request.appDraftId)
                .bindMapLeft(::toServerError)
            ensure(publishedAppCount < PUBLISHED_APP_LIMIT) {
                PublishedAppLimitExceededError(PUBLISHED_APP_LIMIT)
            }

            tx.appDrafts
                .updateSubmitTime(request.appDraftId, timestampSource.now())
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()
    }

    override fun deleteAppDraft(
        context: CallContext,
        request: DeleteAppDraftRequest,
    ): Either<DeleteAppDraftError, Unit> = either {
        dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(HasPermissionRequest.DeleteAppDraft(request.appDraftId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft is guaranteed to exist since permission is granted
            val isSubmitted =
                tx.appDrafts.isSubmitted(request.appDraftId).bindMapLeft(::toServerError)
            ensure(!isSubmitted) { AppDraftSubmittedError(request.appDraftId) }

            tx.appDrafts
                .deleteById(request.appDraftId, timestampSource.now())
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()
    }

    override fun createAppDraftListing(
        context: CallContext,
        request: CreateAppDraftListingRequest,
    ): Either<CreateAppDraftListingError, CreateAppDraftListingResponse> = either {
        val listingId = dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(
                    HasPermissionRequest.CreateAppDraftListing(request.appDraftId, userId)
                )
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft is guaranteed to exist since permission is granted
            val isSubmitted =
                tx.appDrafts.isSubmitted(request.appDraftId).bindMapLeft(::toServerError)
            ensure(!isSubmitted) { AppDraftSubmittedError(request.appDraftId) }

            val listingExists = tx.appDrafts
                .listingExistsByLanguageForAppDraft(request.appDraftId, request.language)
                .bindMapLeft(::toServerError)
            ensure(!listingExists) {
                AppDraftListingAlreadyExistsError(
                    request.appDraftId,
                    request.language.languageTag(),
                )
            }

            val listingId = idGenerator
                .generateId(IdType.APP_DRAFT_LISTING)
                .bindMapLeft(::toServerError)
            tx.appDrafts
                .createListing(
                    id = listingId,
                    appDraftId = request.appDraftId,
                    language = request.language,
                    name = request.name,
                    shortDescription = request.shortDescription,
                )
                .bindMapLeft(::toServerError)

            listingId
        }
            .bindMapLeft(::toServerError)
            .bind()

        CreateAppDraftListingResponse(listingId)
    }

    override fun getAppDraftListing(
        context: CallContext,
        request: GetAppDraftListingRequest,
    ): Either<GetAppDraftListingError, GetAppDraftListingResponse> = either {
        val listing = dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(
                    HasPermissionRequest.ViewAppDraftListing(request.appDraftListingId, userId)
                )
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft listing is guaranteed to exist since permission is granted
            tx.appDrafts
                .requireListingApiViewById(request.appDraftListingId)
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()

        GetAppDraftListingResponse(
            ApiAppDraftListing(
                id = listing.id,
                appDraftId = listing.appDraftId,
                language = listing.language.languageTag(),
                name = listing.name,
                shortDescription = listing.shortDescription,
            )
        )
    }

    override fun listAppDraftListings(
        context: CallContext,
        request: ListAppDraftListingsRequest,
    ): Either<ListAppDraftListingsError, ListAppDraftListingsResponse> = either {
        // We never need a page size beyond Int.MAX_VALUE since we limit the number of app draft
        // listings per app draft well below that, so we can safely coerce the page size down for
        // our DataStore query without changing behavior
        val maxResults = request.pageSize
            .coerceAtMost(Int.MAX_VALUE.toUInt())
            .toInt()
            .let(NonNegativeInt::new)
            .unwrap()
        val lastLanguage = request.nextPageToken.map {
            try {
                val bytes = Base64.UrlSafe.decode(it)
                val token = ListAppDraftListingsPageToken.parseFrom(bytes)
                if (!token.hasLastLanguage()) {
                    raise(InvalidPageTokenError)
                }

                ListingLanguage.fromLanguageTag(token.lastLanguage)
                    .toEitherBind { InvalidPageTokenError }
            } catch (_: IllegalArgumentException) {
                raise(InvalidPageTokenError)
            } catch (_: InvalidProtocolBufferException) {
                raise(InvalidPageTokenError)
            }
        }

        val listings = dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            tx.appDrafts
                .findListingApiViewsForAppDraftAndUserByQuery(
                    appDraftId = request.appDraftId,
                    userId = userId,
                    maxResults = maxResults,
                    afterLanguage = lastLanguage,
                )
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()
            .map { listing ->
                ApiAppDraftListing(
                    id = listing.id,
                    appDraftId = listing.appDraftId,
                    language = listing.language.languageTag(),
                    name = listing.name,
                    shortDescription = listing.shortDescription,
                )
            }
        val nextPageToken = if (listings.isNotEmpty()) {
            val token = listAppDraftListingsPageToken { this.lastLanguage = listings.last().language }
            Some(Base64.UrlSafe.encode(token.toByteArray()))
        } else {
            None
        }

        ListAppDraftListingsResponse(listings, nextPageToken)
    }

    override fun updateAppDraftListing(
        context: CallContext,
        request: UpdateAppDraftListingRequest,
    ): Either<UpdateAppDraftListingError, Unit> = either {
        dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(
                    HasPermissionRequest.UpdateAppDraftListing(request.appDraftListingId, userId)
                )
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft listing is guaranteed to exist since permission is granted
            val listing = tx.appDrafts
                .requireListingApiViewById(request.appDraftListingId)
                .bindMapLeft(::toServerError)

            // App draft is guaranteed to exist since permission is granted
            val isSubmitted =
                tx.appDrafts.isSubmitted(listing.appDraftId).bindMapLeft(::toServerError)
            ensure(!isSubmitted) { AppDraftSubmittedError(listing.appDraftId) }

            tx.appDrafts
                .updateListing(
                    listingId = request.appDraftListingId,
                    name = request.name,
                    shortDescription = request.shortDescription,
                )
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()
    }

    override fun uploadAppDraftListingIcon(
        context: CallContext,
        request: UploadAppDraftListingIconRequest,
    ): Either<UploadAppDraftListingIconError, UploadAppDraftListingIconResponse> = either {
        dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(
                    HasPermissionRequest.UploadAppDraftListingIcon(
                        request.appDraftListingId,
                        userId,
                    )
                )
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft listing is guaranteed to exist since permission is granted
            val listing = tx.appDrafts
                .requireListingApiViewById(request.appDraftListingId)
                .bindMapLeft(::toServerError)

            // App draft is guaranteed to exist since permission is granted
            val isSubmitted =
                tx.appDrafts.isSubmitted(listing.appDraftId).bindMapLeft(::toServerError)
            ensure(!isSubmitted) { AppDraftSubmittedError(listing.appDraftId) }

            // Generate a signed URI for upload
            val objectKey =
                idGenerator.generateId(IdType.BLOB_OBJECT_KEY).bindMapLeft(::toServerError)
            val blobLocation = BlobId.Location(appDraftListingIconUploadBucketName, objectKey)
            val (storageBackend, uploadUri) = objectStorage
                .signUploadUri(UploadType.ICON, blobLocation)
                .bindMapLeft(::toServerError)

            // Persist a pending reference to the blob we intend to create
            val now = timestampSource.now()
            val blobId = idGenerator.generateId(IdType.EXTERNAL_BLOB).bindMapLeft(::toServerError)
            val blob = when (storageBackend) {
                BlobStorageBackend.GCS -> ExternalBlob.Gcs(
                    id = blobId,
                    createTime = now,
                    bucketName = blobLocation.bucketName,
                    objectKey = blobLocation.objectKey,
                    status = ExternalBlob.Status.Pending,
                )

                BlobStorageBackend.LOCAL -> ExternalBlob.Local(
                    id = blobId,
                    createTime = now,
                    bucketName = blobLocation.bucketName,
                    objectKey = blobLocation.objectKey,
                    status = ExternalBlob.Status.Pending,
                )
            }
            // Upsert the app draft listing's pending icon upload
            val pendingUploadExists = tx.appDrafts
                .pendingListingIconUploadExistsByListingId(listing.id)
                .bindMapLeft(::toServerError)
            if (pendingUploadExists) {
                tx.appDrafts
                    .deletePendingListingIconUploadByListingId(listing.id, now)
                    .bindMapLeft(::toServerError)
            }
            tx.appDrafts
                .saveListingIconUpload(
                    PendingAppDraftListingIconUpload.Incomplete(
                        id = idGenerator
                            .generateId(IdType.PENDING_APP_DRAFT_LISTING_ICON_UPLOAD)
                            .bindMapLeft(::toServerError),
                        appDraftListingId = listing.id,
                        objectKey = objectKey,
                        createTime = now,
                        externalBlobId = blob.id,
                    ),
                    blob,
                )
                .bindMapLeft(::toServerError)

            UploadAppDraftListingIconResponse(uploadUri)
        }
            .bindMapLeft(::toServerError)
            .bind()
    }

    override fun downloadAppDraftListingIcon(
        context: CallContext,
        request: DownloadAppDraftListingIconRequest
    ): Either<DownloadAppDraftListingIconError, DownloadAppDraftListingIconResponse> {
        TODO()
    }

    override fun deleteAppDraftListing(
        context: CallContext,
        request: DeleteAppDraftListingRequest
    ): Either<DeleteAppDraftListingError, Unit> = either {
        dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            val hasPermission = tx.authz
                .hasPermission(
                    HasPermissionRequest.DeleteAppDraftListing(request.appDraftListingId, userId)
                )
                .bindMapLeft(::toServerError)
            ensure(hasPermission) { InsufficientPermissionError }

            // App draft listing is guaranteed to exist since permission is granted
            val listing = tx.appDrafts
                .requireListingApiViewById(request.appDraftListingId)
                .bindMapLeft(::toServerError)

            // App draft is guaranteed to exist since permission is granted
            val isSubmitted =
                tx.appDrafts.isSubmitted(listing.appDraftId).bindMapLeft(::toServerError)
            ensure(!isSubmitted) { AppDraftSubmittedError(listing.appDraftId) }

            val isDefaultListing = tx.appDrafts
                .listingIsDefault(request.appDraftListingId)
                .bindMapLeft(::toServerError)
            if (isDefaultListing) {
                tx.appDrafts
                    .updateDefaultListing(listing.appDraftId, None)
                    .bindMapLeft(::toServerError)
            }

            tx.appDrafts
                .deleteListingById(request.appDraftListingId, timestampSource.now())
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()
    }

    override fun publishAppDraft(
        context: CallContext,
        request: PublishAppDraftRequest
    ): Either<PublishAppDraftError, PublishAppDraftResponse> {
        TODO()
    }

    private fun AppDraftApiView.toApiResource(): ApiAppDraft {
        return when (this) {
            is AppDraftApiView.Unsubmitted -> ApiAppDraft.Unsubmitted(
                id = id,
                createTime = createTime,
                defaultAppDraftListingId = defaultAppDraftListingId,
                appPackage = appPackage.map { it.toApiResource() },
            )

            is AppDraftApiView.Submitted -> ApiAppDraft.Submitted(
                id = id,
                createTime = createTime,
                defaultAppDraftListingId = defaultAppDraftListingId,
                appPackage = appPackage.toApiResource(),
                submitTime = submitTime,
            )
        }
    }

    private fun AppPackageApiView.toApiResource(): ApiAppPackage {
        return ApiAppPackage(
            androidApplicationId = androidApplicationId,
            versionCode = versionCode,
            versionName = versionName,
            targetSdk = targetSdk,
        )
    }

    private fun ExternalBlob<ExternalBlob.Status.Committed<*>>.toBlobId(): BlobId {
        return when (val version = this.status.version) {
            is ExternalBlob.GcsBlobVersion -> BlobId.Gcs(
                BlobId.Location(this.bucketName, this.objectKey),
                BlobId.Version.Gcs(version.generation, version.metaGeneration),
            )

            is ExternalBlob.LocalBlobVersion -> BlobId.Local(
                BlobId.Location(this.bucketName, this.objectKey),
                BlobId.Version.Local(version.generation),
            )
        }
    }
}
