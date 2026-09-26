// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.blobstorage

import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.domain.ports.driven.blobstorage.BlobId
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorage
import app.accrescent.server.domain.ports.driven.blobstorage.BlobStorageConformanceTest

class LocalBlobStorageConformanceTest : BlobStorageConformanceTest<BlobId.Local>() {
    override fun <T> withBlobStorage(block: (BlobStorage<BlobId.Local>) -> T): T {
        return LocalBlobStorage(DeterministicRandomSource()).use(block)
    }

    override val bucketName = "test"

    override fun makeBlobId(location: BlobId.Location): BlobId.Local {
        return BlobId.Local(location, BlobId.Version.Local(1L))
    }

    override fun withDifferentVersion(blobId: BlobId.Local): BlobId.Local {
        return blobId.copy(version = BlobId.Version.Local(blobId.version.generation + 1L))
    }
}
