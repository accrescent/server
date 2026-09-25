// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.ports.driving.api.console

import app.accrescent.server.parcelo.domain.IdGenerationError
import app.accrescent.server.parcelo.domain.ports.driven.blobstorage.BlobStorageError
import app.accrescent.server.parcelo.domain.ports.driven.datastore.DataStoreError

sealed interface CreateAppDraftError
sealed interface CreateAppDraftListingError
sealed interface CreateSessionError
sealed interface DeleteAppDraftError
sealed interface DeleteAppDraftListingError
sealed interface DownloadAppDraftError
sealed interface DownloadAppDraftListingIconError
sealed interface GetAppDraftError
sealed interface GetAppDraftListingError
sealed interface GetAppError
sealed interface GetMyOrganizationError
sealed interface ListAppDraftListingsError
sealed interface ListAppDraftsError
sealed interface PublishAppDraftError
sealed interface SubmitAppDraftError
sealed interface UpdateAppDraftError
sealed interface UpdateAppDraftListingError
sealed interface UpdateAppError
sealed interface UploadAppDraftError
sealed interface UploadAppDraftListingIconError

data class ActiveAppDraftLimitExceededError(val limit: ULong) : CreateAppDraftError

data class AppDraftAlreadyPublishedError(val id: String) : PublishAppDraftError

data class AppDraftHasNoDefaultListingError(val id: String) : SubmitAppDraftError

data class AppDraftHasNoPackageError(val id: String) : SubmitAppDraftError

data class AppDraftListingAlreadyExistsError(
    val appDraftId: String,
    val language: String,
) : CreateAppDraftListingError

data class AppDraftListingIconNotFoundError(val appDraftListingId: String) :
    DownloadAppDraftListingIconError

data class AppDraftListingNotFoundError(val id: String) : UpdateAppDraftError

data class AppDraftPackageNotFoundError(val appDraftId: String) : DownloadAppDraftError

data class AppDraftPublishingError(val id: String) : PublishAppDraftError

data class AppDraftSubmittedError(val id: String) :
    CreateAppDraftListingError,
    DeleteAppDraftError,
    DeleteAppDraftListingError,
    SubmitAppDraftError,
    UpdateAppDraftError,
    UpdateAppDraftListingError,
    UploadAppDraftError,
    UploadAppDraftListingIconError

data class AppDraftSubmittedForAppIdError(val appId: String) : SubmitAppDraftError

data object AppWithSameIdAlreadyExists : PublishAppDraftError

data class PublishedAppLimitExceededError(val limit: ULong) : SubmitAppDraftError

data object InsufficientPermissionError :
    CreateAppDraftError,
    CreateAppDraftListingError,
    DeleteAppDraftError,
    DeleteAppDraftListingError,
    DownloadAppDraftError,
    DownloadAppDraftListingIconError,
    GetAppDraftError,
    GetAppDraftListingError,
    GetAppError,
    PublishAppDraftError,
    SubmitAppDraftError,
    UpdateAppDraftError,
    UpdateAppDraftListingError,
    UpdateAppError,
    UploadAppDraftError,
    UploadAppDraftListingIconError

data object InvalidPageTokenError : ListAppDraftListingsError, ListAppDraftsError

data object UnauthenticatedError :
    CreateAppDraftError,
    CreateAppDraftListingError,
    DeleteAppDraftError,
    DeleteAppDraftListingError,
    DownloadAppDraftError,
    DownloadAppDraftListingIconError,
    GetAppDraftError,
    GetAppDraftListingError,
    GetAppError,
    GetMyOrganizationError,
    ListAppDraftListingsError,
    ListAppDraftsError,
    PublishAppDraftError,
    SubmitAppDraftError,
    UpdateAppDraftError,
    UpdateAppDraftListingError,
    UpdateAppError,
    UploadAppDraftError,
    UploadAppDraftListingIconError

sealed interface ServerError :
    CreateAppDraftError,
    CreateAppDraftListingError,
    CreateSessionError,
    DeleteAppDraftError,
    DeleteAppDraftListingError,
    DownloadAppDraftError,
    DownloadAppDraftListingIconError,
    GetAppDraftError,
    GetAppDraftListingError,
    GetAppError,
    GetMyOrganizationError,
    ListAppDraftListingsError,
    ListAppDraftsError,
    PublishAppDraftError,
    SubmitAppDraftError,
    UpdateAppDraftError,
    UpdateAppDraftListingError,
    UpdateAppError,
    UploadAppDraftError,
    UploadAppDraftListingIconError

data object InternalServerError : ServerError

data object ServiceUnavailableError : ServerError

@JvmName("dataStoreToServerError")
fun toServerError(error: DataStoreError): ServerError {
    return when (error) {
        is DataStoreError.ConsistencyViolation,
        is DataStoreError.EntityNotFound,
        is DataStoreError.IllegalState,
        is DataStoreError.RollbackErrorOnCommit,
            // Because we value correctness, we choose to take the conservative approach here of
            // assuming that unknown errors may be critical ones so that we prioritize them.
        is DataStoreError.Unknown -> InternalServerError

        is DataStoreError.SerializationFailure -> ServiceUnavailableError
    }
}

@JvmName("idGeneratorToServerError")
fun toServerError(error: IdGenerationError): ServerError = ServiceUnavailableError

@JvmName("objectStorageToServerError")
fun toServerError(error: BlobStorageError): ServerError = ServiceUnavailableError
