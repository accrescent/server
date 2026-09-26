// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.async

sealed class UploadProcessingError {
    /**
     * No pending upload record was found for the event.
     */
    data object NoPendingUpload : UploadProcessingError()

    /**
     * The pending upload already has a result.
     */
    data object AlreadyProcessed : UploadProcessingError()

    /**
     * The event is stale, having already been superseded by another event.
     */
    data object StaleEvent : UploadProcessingError()

    /**
     * The event's referenced blob could not be found in blob storage.
     */
    data object BlobNotFound : UploadProcessingError()

    /**
     * An internal processing error has occurred.
     */
    data object Internal : UploadProcessingError()
}