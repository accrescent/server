// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

import app.accrescent.server.core.Bytes
import app.accrescent.server.domain.android.ApplicationId
import app.accrescent.server.domain.android.SdkVersion
import app.accrescent.server.domain.android.VersionCode
import app.accrescent.server.domain.android.VersionName
import java.time.OffsetDateTime

data class AppPackage(
    val id: String,
    val appDraftId: String,
    val externalBlobId: String,
    val uploadEventTime: OffsetDateTime,
    val appId: ApplicationId,
    val versionCode: VersionCode,
    val versionName: VersionName,
    val targetSdk: SdkVersion,
    val signerCertificate: Bytes,
    val buildApksResult: Bytes,
)
