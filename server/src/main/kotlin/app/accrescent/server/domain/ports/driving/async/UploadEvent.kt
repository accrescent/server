// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.async

import java.time.OffsetDateTime

sealed interface UploadEvent {
    val bucketName: String
    val objectKey: String
    val eventTime: OffsetDateTime

    data class Local(
        override val bucketName: String,
        override val objectKey: String,
        override val eventTime: OffsetDateTime,
        val generation: Long,
    ) : UploadEvent

    data class Gcs(
        override val bucketName: String,
        override val objectKey: String,
        override val eventTime: OffsetDateTime,
        val generation: Long,
        val metaGeneration: Long,
    ) : UploadEvent
}
