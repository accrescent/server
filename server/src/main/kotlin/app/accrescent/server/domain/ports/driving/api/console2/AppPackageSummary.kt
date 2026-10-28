// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import app.accrescent.server.domain.android.ApplicationId
import app.accrescent.server.domain.android.VersionName

/**
 * A summary of an app package.
 *
 * @property androidApplicationId the package's Android application ID.
 * @property versionName the package's version name.
 */
data class AppPackageSummary(
    val androidApplicationId: ApplicationId,
    val versionName: VersionName,
)
