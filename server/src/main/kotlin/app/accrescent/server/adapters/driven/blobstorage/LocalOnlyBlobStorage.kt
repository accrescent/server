// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.blobstorage

import app.accrescent.server.domain.ports.driven.blobstorage.BlobId
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorage
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorageBackend
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorageError
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorageResult
import app.accrescent.server.domain.ports.driven.blobstorage.UploadType
import app.accrescent.server.domain.ports.driven.blobstorage.UriLifetime
import app.accrescent.server.domain.uri.HttpUri
import arrow.core.raise.context.bind
import arrow.core.raise.context.either
import arrow.core.raise.context.ensure
import java.nio.file.Path

class LocalOnlyBlobStorage(
    private val delegate: LocalBlobStorage,
) : BlobStorage<BlobId> {
    override fun signUploadUri(
        type: UploadType,
        location: BlobId.Location,
        lifetime: UriLifetime,
        maxSizeBytes: ULong,
    ): BlobStorageResult<Pair<BlobStorageBackend, HttpUri>> {
        return delegate.signUploadUri(type, location, lifetime, maxSizeBytes)
    }

    override fun signDownloadUri(
        blobId: BlobId,
        lifetime: UriLifetime
    ): BlobStorageResult<HttpUri> = either {
        ensure(blobId is BlobId.Local) { BlobStorageError.Other }

        delegate.signDownloadUri(blobId, lifetime).bind()
    }

    override fun copy(
        source: BlobId,
        destination: BlobId.Location
    ): BlobStorageResult<BlobId> = either {
        ensure(source is BlobId.Local) { BlobStorageError.Other }

        delegate.copy(source, destination).bind()
    }

    override fun create(
        contents: ByteArray,
        destination: BlobId.Location,
    ): BlobStorageResult<BlobId> {
        return delegate.create(contents, destination)
    }

    override fun upload(
        source: Path,
        destination: BlobId.Location,
    ): BlobStorageResult<BlobId> {
        return delegate.upload(source, destination)
    }

    override fun download(blobId: BlobId, destination: Path): BlobStorageResult<Unit> = either {
        ensure(blobId is BlobId.Local) { BlobStorageError.Other }

        return delegate.download(blobId, destination)
    }
}
