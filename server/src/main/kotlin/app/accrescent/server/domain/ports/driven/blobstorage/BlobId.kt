// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.blobstorage

import app.accrescent.server.domain.ports.driven.datastore.ExternalBlob

/**
 * A reference to a specific version of an object in object storage.
 *
 * An object ID pairs the [location] of an object with the version assigned to it by the service it
 * is stored on. It mirrors [ExternalBlob] in that both the service an object originates from and
 * the service-assigned version of the object are encoded as location-dependent types.
 *
 * Objects which have not yet been uploaded have no version and are instead represented by a bare
 * [Location], e.g., as returned by [BlobStorage.signUploadUri].
 *
 * @property location the service-agnostic location the object is stored at.
 */
sealed interface BlobId {
    val location: Location

    /**
     * A blob's service-agnostic location.
     *
     * @property bucketName the name of the blob's containing bucket.
     * @property objectKey the blob's unique key within its bucket.
     */
    data class Location(val bucketName: String, val objectKey: String)

    /**
     * An object stored on the local filesystem.
     *
     * @property location the location the object is stored at.
     * @property version the version assigned to the object by the local filesystem service.
     */
    data class Local(
        override val location: Location,
        val version: Version.Local,
    ) : BlobId

    /**
     * An object stored in Google Cloud Storage.
     *
     * @property location the location the object is stored at.
     * @property version the version assigned to the object by Google Cloud Storage.
     */
    data class Gcs(
        override val location: Location,
        val version: Version.Gcs,
    ) : BlobId

    /**
     * The version of an object assigned by the service it is stored on.
     *
     * Each service assigns versions in its own format, so the version's type depends on the service
     * the object originates from.
     */
    sealed interface Version {
        /**
         * The version of an object stored on the local filesystem.
         *
         * @property generation the opaque version assigned by the local filesystem service.
         */
        data class Local(val generation: Long) : Version

        /**
         * The version of an object stored in Google Cloud Storage.
         *
         * For further details, see
         * [Google's documentation](https://docs.cloud.google.com/storage/docs/metadata#generation-number).
         *
         * @property generation the generation assigned by Google Cloud Storage.
         * @property metaGeneration the metageneration number assigned by Google Cloud Storage.
         */
        data class Gcs(val generation: Long, val metaGeneration: Long) : Version
    }
}
