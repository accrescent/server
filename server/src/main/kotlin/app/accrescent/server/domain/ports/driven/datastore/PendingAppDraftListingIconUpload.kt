// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

import java.time.OffsetDateTime

sealed class AppDraftListingIconUploadProcessingResult {
    data object Success : AppDraftListingIconUploadProcessingResult()

    sealed class Error : AppDraftListingIconUploadProcessingResult() {
        /**
         * The app draft is immutable because it has already been submitted.
         */
        data object AppDraftSubmitted : Error()

        /**
         * The uploaded file is not a valid PNG image.
         */
        data object InvalidImage : Error()

        /**
         * The uploaded image does not have the required dimensions.
         */
        data object IncorrectImageDimensions : Error()
    }
}

sealed class PendingAppDraftListingIconUpload {
    abstract val id: String
    abstract val appDraftListingId: String
    abstract val objectKey: String
    abstract val createTime: OffsetDateTime

    data class Incomplete(
        override val id: String,
        override val appDraftListingId: String,
        override val objectKey: String,
        override val createTime: OffsetDateTime,
        val externalBlobId: String,
    ) : PendingAppDraftListingIconUpload()

    data class Completed(
        override val id: String,
        override val appDraftListingId: String,
        override val objectKey: String,
        override val createTime: OffsetDateTime,
        val result: AppDraftListingIconUploadProcessingResult,
    ) : PendingAppDraftListingIconUpload()
}
