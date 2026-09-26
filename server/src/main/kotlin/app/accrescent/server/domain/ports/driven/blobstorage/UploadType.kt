// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.blobstorage

enum class UploadType(val maxSizeBytes: ULong) {
    // 1 GiB limit
    APK_SET(1073741824uL),

    // 1 MiB limit
    ICON(1048576uL),
}
