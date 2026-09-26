// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console

import arrow.core.Option

data class UpdateAppDraftListingRequest(
    val appDraftListingId: String,
    val name: Option<String>,
    val shortDescription: Option<String>,
)
