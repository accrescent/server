// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.blobstorage

import app.accrescent.server.domain.uri.HttpUri
import arrow.core.Either
import java.nio.file.Path

typealias BlobStorageResult<T> = Either<BlobStorageError, T>

sealed class BlobStorageError {
    data object NotFound : BlobStorageError()
    data object Other : BlobStorageError()
}

interface BlobStorage<B : BlobId> {
    fun signUploadUri(
        type: UploadType,
        location: BlobId.Location,
        lifetime: UriLifetime = UriLifetime.DEFAULT,
        maxSizeBytes: ULong = type.maxSizeBytes,
    ): BlobStorageResult<Pair<BlobStorageBackend, HttpUri>>

    fun signDownloadUri(
        blobId: B,
        lifetime: UriLifetime = UriLifetime.DEFAULT,
    ): BlobStorageResult<HttpUri>

    fun copy(source: B, destination: BlobId.Location): BlobStorageResult<B>

    fun create(contents: ByteArray, destination: BlobId.Location): BlobStorageResult<B>

    fun upload(source: Path, destination: BlobId.Location): BlobStorageResult<B>

    /**
     * Downloads the blob identified by [blobId] to [destination], overwriting any file that already
     * exists there.
     *
     * @param blobId the ID of the blob to download.
     * @param destination the local path to write the object's contents to.
     * @return [BlobStorageError.NotFound] if no blob exists for [blobId].
     */
    fun download(blobId: B, destination: Path): BlobStorageResult<Unit>
}
