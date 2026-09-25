// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.ports.driving.api.console

import arrow.core.Option

data class ListAppDraftsRequest(
    val organizationId: String,
    val pageSize: UInt,
    val pageToken: Option<String>,
)
