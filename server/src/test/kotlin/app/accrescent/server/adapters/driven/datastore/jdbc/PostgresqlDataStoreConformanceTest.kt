// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore.jdbc

import app.accrescent.server.PostgresqlContainer
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.datastore.DataStoreConformanceTest
import javax.sql.DataSource

class PostgresqlDataStoreConformanceTest : DataStoreConformanceTest() {
    override fun <T> withDataStore(block: (DataStore) -> T): T {
        return PostgresqlContainer.withNewDatabase { dataSource ->
            block(dataStoreFor(dataSource))
        }
    }

    // Migrations take a significant amount of test time, so speed up the tests by migrating the
    // database once, creating a template database, and then reusing that migrated template.
    override fun <T> withMigratedDataStore(block: (DataStore) -> T): T {
        return migratedTemplate.withNewDatabase { dataSource -> block(dataStoreFor(dataSource)) }
    }

    private companion object {
        private val migratedTemplate = PostgresqlContainer.TemplateDatabase { dataSource ->
            dataStoreFor(dataSource).migrateToHead().unwrap()
        }

        private fun dataStoreFor(dataSource: DataSource): PostgresqlDataStore {
            return PostgresqlDataStore(dataSource, DeterministicRandomSource())
        }
    }
}
