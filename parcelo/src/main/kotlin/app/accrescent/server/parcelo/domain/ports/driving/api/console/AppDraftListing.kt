// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.ports.driving.api.console

data class AppDraftListing(
    val id: String,
    val appDraftId: String,
    val language: String,
    val name: String,
    val shortDescription: String,
)
