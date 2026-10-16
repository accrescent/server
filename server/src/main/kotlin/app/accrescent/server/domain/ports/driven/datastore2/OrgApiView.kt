// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

import app.accrescent.server.core.NonNegativeLong

/**
 * The view of an organization exposed through the API.
 *
 * @property id the organization's unique ID.
 * @property publishedAppCount the current number of apps published under the organization.
 * @property activeAppDraftCount the current number of active app drafts under the organization.
 */
data class OrgApiView(
    val id: String,
    val publishedAppCount: NonNegativeLong,
    val activeAppDraftCount: NonNegativeLong,
)
