// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreConformanceTest
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterAll

/**
 * [DataStore] conformance test suite for [MemoryDataStore].
 */
class MemoryDataStoreConformanceTest : DataStoreConformanceTest() {
    override suspend fun <T> withDataStore(block: suspend (DataStore) -> T): T {
        val dataStore = MemoryDataStore(vertx)
        val result = block(dataStore)
        dataStore.close().unwrap()

        return result
    }

    private companion object {
        private val vertx = Vertx.vertx()

        @AfterAll
        @JvmStatic
        fun closeVertx() {
            vertx.close().await()
        }
    }
}
