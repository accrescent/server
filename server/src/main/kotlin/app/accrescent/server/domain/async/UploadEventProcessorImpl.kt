// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.async

import app.accrescent.server.core.Bytes
import app.accrescent.server.core.UseError
import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.core.use
import app.accrescent.server.domain.IdGenerationError
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.IdType
import app.accrescent.server.domain.android.ApkSet
import app.accrescent.server.domain.ports.driven.blobstorage.BlobId
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorage
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorageError
import app.accrescent.server.domain.ports.driven.datastore.AppDraftUploadProcessingError
import app.accrescent.server.domain.ports.driven.datastore.AppPackage
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.datastore.DataStoreError
import app.accrescent.server.domain.ports.driven.datastore.ExternalBlob
import app.accrescent.server.domain.ports.driven.datastore.PendingAppDraftUpload
import app.accrescent.server.domain.ports.driven.file.TempFile
import app.accrescent.server.domain.ports.driven.file.TempFileCloseError
import app.accrescent.server.domain.ports.driven.file.TempFileCreateError
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.async.EventResponder
import app.accrescent.server.domain.ports.driving.async.UploadEvent
import app.accrescent.server.domain.ports.driving.async.UploadEventProcessor
import app.accrescent.server.domain.ports.driving.async.UploadProcessingError
import arrow.core.Either
import arrow.core.flatten
import arrow.core.raise.ensure
import java.nio.file.Path

class UploadEventProcessorImpl(
    private val dataStore: DataStore,
    private val blobStorage: BlobStorage<BlobId>,
    private val idGenerator: IdGenerator,
    private val timestampSource: TimestampSource,
    private val tempFileFactory: TempFile.Factory<*>,
    private val downloadDirectory: Path,
) : UploadEventProcessor {
    override fun processAppDraftUpload(
        event: UploadEvent,
        responder: EventResponder,
    ): Either<UploadProcessingError, Unit> {
        val processingResult = dataStore.runTxWithRetry { tx ->
            val blobId = event.toBlobId()
            val pendingUpload = tx.appDrafts
                .findPendingUploadByObjectKey(event.objectKey)
                .bindMapLeft(::toProcessingError)
                // We have no record of this upload anymore, so there's nothing to do
                .toEitherBind { UploadProcessingError.NoPendingUpload }
            // This upload has already been processed - no need to try again
            ensure(pendingUpload is PendingAppDraftUpload.Incomplete) {
                UploadProcessingError.AlreadyProcessed
            }

            // App draft is guaranteed to exist since the pending upload exists
            val isSubmitted = tx.appDrafts
                .isSubmitted(pendingUpload.appDraftId)
                .bindMapLeft(::toProcessingError)
            val currentPackage = tx.appPackages
                .findByAppDraftId(pendingUpload.appDraftId)
                .bindMapLeft(::toProcessingError)
            // If the app draft has a package and this upload event isn't newer than the package's
            // upload event, it's stale, so we should skip processing it
            ensure(!currentPackage.isSome { !event.eventTime.isAfter(it.uploadEventTime) }) {
                UploadProcessingError.StaleEvent
            }

            // Submitted app drafts are immutable, so we should refuse updating their packages
            val now = timestampSource.now()
            if (isSubmitted) {
                tx.appDrafts
                    .completePendingUpload(
                        pendingUpload.id,
                        AppDraftUploadProcessingError.AppDraftSubmitted,
                        now,
                    )
                    .bindMapLeft(::toProcessingError)
                return@runTxWithRetry
            }

            // Download the blob and parse it as an APK set
            val apkSet = tempFileFactory
                .createInDirectory(downloadDirectory)
                .bindMapLeft(::toProcessingError)
                .use { tempFile ->
                    blobStorage.download(blobId, tempFile.path).bindMapLeft(::toUseError)

                    ApkSet.parse(tempFile.path, downloadDirectory, tempFileFactory, now)
                }
                .bindMapLeft(::toProcessingError)
                .fold(
                    { error ->
                        tx.appDrafts
                            .completePendingUpload(
                                pendingUpload.id,
                                AppDraftUploadProcessingError.ApkSetParseFailed(error),
                                now,
                            )
                            .bindMapLeft(::toProcessingError)
                        return@runTxWithRetry
                    },
                    { it },
                )

            // Copy the blob to its reserved location in a private bucket so it cannot be overwritten
            val externalBlob = tx.externalBlobs
                .requireById(pendingUpload.externalBlobId)
                .bindMapLeft(::toProcessingError)
            val newBlobId = blobStorage
                .copy(blobId, BlobId.Location(externalBlob.bucketName, externalBlob.objectKey))
                .bindMapLeft(::toProcessingError)

            // Commit the blob into a new package for the app draft now that it's written to private
            // storage. In the process, delete any existing package, mark its blob for deletion, and
            // mark the pending upload as successful.
            val version = when (newBlobId) {
                is BlobId.Gcs -> ExternalBlob.GcsBlobVersion(
                    newBlobId.version.generation,
                    newBlobId.version.metaGeneration,
                )

                is BlobId.Local -> ExternalBlob.LocalBlobVersion(newBlobId.version.generation)
            }
            val appPackageId =
                idGenerator.generateId(IdType.APP_PACKAGE).bindMapLeft(::toProcessingError)
            tx.appPackages.saveFromPendingUpload(
                pendingUploadId = pendingUpload.id,
                appPackage = AppPackage(
                    id = appPackageId,
                    appDraftId = pendingUpload.appDraftId,
                    externalBlobId = externalBlob.id,
                    uploadEventTime = event.eventTime,
                    appId = apkSet.applicationId,
                    versionCode = apkSet.versionCode,
                    versionName = apkSet.versionName,
                    targetSdk = apkSet.targetSdk,
                    signerCertificate = Bytes(apkSet.signerCertificate.encoded),
                    buildApksResult = Bytes(apkSet.buildApksResult.toByteArray()),
                ),
                permissions = apkSet.permissions,
                blobVersion = version,
                replacedBlobDeleteTime = now,
            )
                .bindMapLeft(::toProcessingError)
        }

        // Always acknowledge the upload event to prevent clogging the event queue. Errors will show
        // themselves as processing timeout errors.
        responder.ack()

        return processingResult.mapLeft(::toProcessingError).flatten()
    }

    override fun processAppDraftListingIconUpload(
        event: UploadEvent,
        responder: EventResponder
    ): Either<UploadProcessingError, Unit> {
        TODO()
    }
}

private sealed class ApkSetBlobUseError {
    data object BlobNotFound : ApkSetBlobUseError()
    data object Internal : ApkSetBlobUseError()
}

private fun UploadEvent.toBlobId(): BlobId {
    return when (this) {
        is UploadEvent.Gcs -> BlobId.Gcs(
            BlobId.Location(this.bucketName, this.objectKey),
            BlobId.Version.Gcs(this.generation, this.metaGeneration),
        )

        is UploadEvent.Local -> BlobId.Local(
            BlobId.Location(this.bucketName, this.objectKey),
            BlobId.Version.Local(this.generation),
        )
    }
}

private fun toUseError(error: BlobStorageError): ApkSetBlobUseError {
    return when (error) {
        BlobStorageError.NotFound -> ApkSetBlobUseError.BlobNotFound
        BlobStorageError.Other -> ApkSetBlobUseError.Internal
    }
}

private fun toProcessingError(error: BlobStorageError): UploadProcessingError {
    return when (error) {
        BlobStorageError.NotFound -> UploadProcessingError.BlobNotFound
        BlobStorageError.Other -> UploadProcessingError.Internal
    }
}

private fun toProcessingError(error: DataStoreError): UploadProcessingError {
    return when (error) {
        else -> UploadProcessingError.Internal
    }
}

private fun toProcessingError(error: IdGenerationError): UploadProcessingError {
    return when (error) {
        else -> UploadProcessingError.Internal
    }
}

private fun toProcessingError(error: TempFileCreateError): UploadProcessingError {
    return when (error) {
        else -> UploadProcessingError.Internal
    }
}

private fun toProcessingError(
    error: UseError<ApkSetBlobUseError, TempFileCloseError>,
): UploadProcessingError {
    return when (error) {
        is UseError.Block -> when (error.error) {
            ApkSetBlobUseError.BlobNotFound -> UploadProcessingError.BlobNotFound
            ApkSetBlobUseError.Internal -> UploadProcessingError.Internal
        }
        // Prioritize returning the block error over the close error if both occurred
        is UseError.Both -> when (error.blockError) {
            ApkSetBlobUseError.BlobNotFound -> UploadProcessingError.BlobNotFound
            ApkSetBlobUseError.Internal -> UploadProcessingError.Internal
        }

        is UseError.Close -> UploadProcessingError.Internal
    }
}
