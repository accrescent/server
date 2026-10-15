// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import app.accrescent.server.PostgresqlContainer
import app.accrescent.server.core.NonEmptyString
import app.accrescent.server.core.TcpPort
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.config.DataStoreConfig
import app.accrescent.server.domain.config.PostgresqlEndpoint
import app.accrescent.server.domain.config.PostgresqlPasswordCredentials
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreConformanceTest
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterAll

/**
 * [DataStore] conformance test suite for [PostgresqlDataStore].
 */
class PostgresqlDataStoreConformanceTest : DataStoreConformanceTest() {
    override suspend fun <T> withDataStore(block: suspend (DataStore) -> T): T {
        return PostgresqlContainer.withNewDatabaseSuspending { database ->
            val config = DataStoreConfig.Postgresql(
                endpoint = PostgresqlEndpoint.Direct(
                    host = NonEmptyString.fromString(database.host).unwrap(),
                    // Convert via a string so that out-of-range ports throw rather than truncate
                    port = TcpPort.new(database.port.toString().toUShort()).unwrap(),
                    credentials = PostgresqlPasswordCredentials(
                        user = NonEmptyString.fromString(database.username).unwrap(),
                        password = NonEmptyString.fromString(database.password).unwrap(),
                    ),
                ),
                database = NonEmptyString.fromString(database.name).unwrap(),
            )
            val dataStore = PostgresqlDataStore(vertx, config)

            try {
                block(dataStore)
            } finally {
                dataStore.close().unwrap()
            }
        }
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
