// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console2

import app.accrescent.server.core.NonNegativeLong

/**
 * An organization of users.
 *
 * @property id this organization's unique ID.
 * @property publishedAppLimit the maximum number of published apps allowed to exist under this
 * organization.
 * @property publishedAppCount the current number of apps published under this organization.
 * @property activeAppDraftLimit the maximum number of active app drafts allowed to exist under this
 * organization.
 * @property activeAppDraftCount the current number of active app drafts under this organization.
 */
data class Organization(
    val id: String,
    val publishedAppLimit: NonNegativeLong,
    val publishedAppCount: NonNegativeLong,
    val activeAppDraftLimit: NonNegativeLong,
    val activeAppDraftCount: NonNegativeLong,
)
