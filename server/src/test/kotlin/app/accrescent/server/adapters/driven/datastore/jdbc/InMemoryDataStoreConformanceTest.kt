// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore.jdbc

import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.datastore.DataStoreConformanceTest

class InMemoryDataStoreConformanceTest : DataStoreConformanceTest() {
    override fun <T> withDataStore(block: (DataStore) -> T): T {
        return InMemoryDataStore(DeterministicRandomSource()).use(block)
    }
}
