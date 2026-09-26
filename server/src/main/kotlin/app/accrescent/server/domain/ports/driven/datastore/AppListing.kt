// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

import app.accrescent.server.domain.appstore.ListingLanguage

data class AppListing(val id: String, val appId: String, val language: ListingLanguage)
