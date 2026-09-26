// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.async

import arrow.core.Either

/**
 * Handler for file upload events.
 *
 * Upload events may be delivered multiple times, out of order, and/or to multiple processors
 * simultaneously. Thus, to ensure correct operation, all event handling operations are idempotent,
 * will ignore previously seen events, and isolate processing in a serializable manner.
 */
interface UploadEventProcessor {
    /**
     * Processes an app draft upload event.
     *
     * @param event the app draft upload event to process.
     * @param responder the responder used to ack the event.
     */
    fun processAppDraftUpload(
        event: UploadEvent,
        responder: EventResponder,
    ): Either<UploadProcessingError, Unit>

    /**
     * Processes an app draft listing icon upload event.
     *
     * @param event the app draft listing icon upload event to process.
     * @param responder the responder used to ack the event.
     */
    fun processAppDraftListingIconUpload(
        event: UploadEvent,
        responder: EventResponder,
    ): Either<UploadProcessingError, Unit>
}
