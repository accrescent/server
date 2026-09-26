// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import java.time.OffsetDateTime

/**
 * A reference to a blob on an external blob storage system.
 *
 * A blob is in one of three states: pending, committed, and deleted. Pending blobs may or may not
 * exist in the blob storage system at their location; they represent blobs which are awaiting
 * upload. Committed blobs are guaranteed to be available in blob storage. Deleted blobs, like
 * pending blobs, may or may not exist in blob storage; they represent blobs which are irreversibly
 * marked as deleted to be later removed from blob storage. Once a blob is marked as deleted, its
 * status must never change back to committed since the referenced blob might be removed by a
 * cleanup process.
 *
 * @param S the type of the blob's [status].
 * @property id the unique identifier of this blob reference.
 * @property createTime the time at which the blob reference was created.
 * @property bucketName the name of the bucket the blob is contained in.
 * @property objectKey the name of the blob within its bucket.
 * @property status the current state of the blob.
 */
sealed interface ExternalBlob<out S : ExternalBlob.Status<*>> {
    val id: String
    val createTime: OffsetDateTime
    val bucketName: String
    val objectKey: String
    val status: S

    /**
     * A blob stored on the local filesystem.
     *
     * Only one blob can exist at a time for each ([bucketName], [objectKey]) pair.
     */
    data class Local<out S : Status<LocalBlobVersion>>(
        override val id: String,
        override val createTime: OffsetDateTime,
        override val bucketName: String,
        override val objectKey: String,
        override val status: S,
    ) : ExternalBlob<S>

    /**
     * A blob stored in Google Cloud Storage.
     *
     * Only one blob can exist at a time for each ([bucketName], [objectKey]) pair.
     */
    data class Gcs<out S : Status<GcsBlobVersion>>(
        override val id: String,
        override val createTime: OffsetDateTime,
        override val bucketName: String,
        override val objectKey: String,
        override val status: S,
    ) : ExternalBlob<S>

    /**
     * The service-assigned version of a committed or deleted blob.
     */
    sealed interface BlobVersion

    /**
     * The service-assigned version of a committed or deleted [Local] blob.
     *
     * @property generation the generation number of the blob.
     */
    data class LocalBlobVersion(val generation: Long) : BlobVersion

    /**
     * The service-assigned version of a committed or deleted [Gcs] blob.
     *
     * @property generation the generation number of the blob.
     * @property metaGeneration the metageneration number of the blob.
     */
    data class GcsBlobVersion(val generation: Long, val metaGeneration: Long) : BlobVersion

    /**
     * The state of a blob.
     *
     * @param V the type of the blob's version assigned once it has been persisted.
     */
    sealed interface Status<out V : BlobVersion> {
        /**
         * A blob which is awaiting upload and may or may not yet exist in blob storage.
         */
        data object Pending : Status<Nothing>

        /**
         * A blob which is guaranteed to be available in blob storage.
         *
         * @param V the type of the blob's version assigned once it has been persisted.
         * @property version the blob's version assigned by the blob storage service once it has
         * been persisted.
         */
        data class Committed<out V : BlobVersion>(val version: V) : Status<V>

        /**
         * A blob which is irreversibly marked as deleted to later be removed from blob storage.
         *
         * @param V the type of the blob's version assigned once it has been persisted.
         * @property version the blob's version assigned by the blob storage service, or [None] if
         * the blob was marked as deleted while still pending and was therefore never assigned one.
         * @property deleteTime the time at which the blob was marked as deleted.
         */
        data class Deleted<out V : BlobVersion>(
            val version: Option<V>,
            val deleteTime: OffsetDateTime,
        ) : Status<V>

        /**
         * The blob's version assigned by the blob storage service, or [None] if the blob has not
         * been assigned one.
         */
        val optionalVersion: Option<V>
            get() = when (this) {
                is Committed -> Some(this.version)
                is Deleted -> this.version
                Pending -> None
            }
    }
}
