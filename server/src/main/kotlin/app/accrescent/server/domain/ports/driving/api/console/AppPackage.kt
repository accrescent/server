// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console

import app.accrescent.server.domain.android.ApplicationId
import app.accrescent.server.domain.android.SdkVersion
import app.accrescent.server.domain.android.VersionCode
import app.accrescent.server.domain.android.VersionName

data class AppPackage(
    val androidApplicationId: ApplicationId,
    val versionCode: VersionCode,
    val versionName: VersionName,
    val targetSdk: SdkVersion,
)
