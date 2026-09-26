// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

data class App(
    val id: String,
    val organizationId: String,
    val defaultAppListingId: String,
    val publiclyListed: Boolean,
)
