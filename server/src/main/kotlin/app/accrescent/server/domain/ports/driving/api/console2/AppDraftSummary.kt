// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import app.accrescent.server.domain.uri.HttpUri
import arrow.core.Option

/**
 * A summary of an app draft.
 *
 * @property id the app draft's unique ID.
 * @property name the app's name from the draft's default listing, if set.
 * @property iconUri the URI of the icon from the draft's default listing, if set.
 * @property appPackage a summary of the draft's uploaded package, if available.
 */
data class AppDraftSummary(
    val id: String,
    val name: Option<String>,
    val iconUri: Option<HttpUri>,
    val appPackage: Option<AppPackageSummary>,
)
