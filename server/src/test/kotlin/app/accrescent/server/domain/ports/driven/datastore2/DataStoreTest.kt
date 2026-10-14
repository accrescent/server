// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore2

import app.accrescent.server.adapters.driven.datastore2.vertx.MemoryDataStore
import app.accrescent.server.core.unwrap
import app.accrescent.server.core.unwrapErr
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class DataStoreTest {
    @Test
    suspend fun `requireOrgIdByOwnerUserId returns EntityNotFound for non-existent user`() {
        withMigratedDataStore { dataStore ->
            val error = dataStore.runInTransaction { tx ->
                tx.requireOrgIdByOwnerUserId("user1").bind()
            }
                .unwrap()
                .unwrapErr()

            assertInstanceOf<DataStoreError.EntityNotFound>(error)
        }
    }

    private companion object {
        private val vertx = Vertx.vertx()

        @AfterAll
        @JvmStatic
        fun closeVertx() {
            vertx.close().await()
        }

        private suspend fun withMigratedDataStore(block: suspend (DataStore) -> Unit) {
            val dataStore = MemoryDataStore(vertx)
            try {
                dataStore.migrateToHead().unwrap()
                block(dataStore)
            } finally {
                dataStore.close().unwrap()
            }
        }
    }
}
