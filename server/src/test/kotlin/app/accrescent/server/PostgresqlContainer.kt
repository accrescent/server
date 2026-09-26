// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import javax.sql.DataSource

/**
 * A shareable PostgreSQL container instance.
 *
 * This object manages a PostgreSQL container instance which is shared across all consumers. It
 * starts its container lazily; if no consumers call any methods which require a running container,
 * it is not started.
 */
object PostgresqlContainer {
    // The minimum supported version of PostgreSQL is 17, so we test against that.
    private val POSTGRESQL_IMAGE = DockerImageName
        .parse(
            "postgres:17@sha256:7958605b474b3d264a969cb3a123d6aa00ad1e1fe9da8a69984dabb704d93317"
        )
        .asCompatibleSubstituteFor("postgres")

    // Start the container lazily so that we don't require a Docker runtime or pay the container
    // startup time when we don't need it
    private val container by lazy { PostgreSQLContainer(POSTGRESQL_IMAGE).apply { start() } }

    private val defaultDataSource by lazy { dataSourceFor(container.databaseName) }

    /**
     * Runs a lambda with a [DataSource] for a new, empty database.
     *
     * Each call creates a new database, so the data accessible through the provided [DataSource] is
     * not shared with any other caller. Attempts to use the [DataSource] outside of [block]'s scope
     * result in undefined behavior.
     *
     * @param block the lambda to run with access to the new database.
     * @return the return value of [block].
     */
    fun <T> withNewDatabase(block: (DataSource) -> T): T {
        return withNewDatabaseFrom("template1", block)
    }

    /**
     * Runs a lambda with a [DataSource] for a newly created copy of a template database.
     *
     * @param templateName the name of the template database to copy.
     * @param block the lambda to run with access to the new database.
     * @return the return value of [block].
     */
    private fun <T> withNewDatabaseFrom(templateName: String, block: (DataSource) -> T): T {
        val databaseName = "test_${UUID.randomUUID()}"

        executeOnDefaultDatabase("CREATE DATABASE \"$databaseName\" TEMPLATE \"$templateName\"")

        return AutoCloseable { executeOnDefaultDatabase("DROP DATABASE \"$databaseName\"") }
            .use { block(dataSourceFor(databaseName)) }
    }

    /**
     * Constructs a [DataSource] for a given database.
     *
     * @param databaseName the name of the database to construct a [DataSource] for.
     * @return a [DataSource] for the database named by [databaseName].
     */
    private fun dataSourceFor(databaseName: String): DataSource {
        val port = container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)

        return PGSimpleDataSource().apply {
            setUrl("jdbc:postgresql://${container.host}:$port/$databaseName")
            user = container.username
            password = container.password
        }
    }

    /**
     * Runs a statement against the container's default database.
     *
     * Certain statements such as CREATE DATABASE cannot run inside a transaction block or while
     * connected to the database they operate on, so they are most easy to execute on the default
     * database; this function is for executing such statements.
     *
     * @param sql the SQL statement to execute.
     */
    private fun executeOnDefaultDatabase(sql: String) {
        defaultDataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    /**
     * A PostgreSQL
     * [database template](https://www.postgresql.org/docs/18/manage-ag-templatedbs.html).
     *
     * This class can be used to create a database template from which additional databases can be
     * created via [withNewDatabase].
     *
     * @param initialize the lambda which populates the template.
     */
    class TemplateDatabase(initialize: (DataSource) -> Unit) {
        private val name = "template_${UUID.randomUUID()}"

        init {
            executeOnDefaultDatabase("CREATE DATABASE \"$name\"")
            initialize(dataSourceFor(name))
        }

        /**
         * Runs a lambda with a [DataSource] for a newly created copy of this template.
         *
         * This method behaves like [PostgresqlContainer.withNewDatabase] except the new database
         * is copied from this template rather than PostgreSQL's default of template1.
         *
         * @param block the lambda to run with access to a new copy of this template.
         * @return the return value of [block].
         */
        fun <T> withNewDatabase(block: (DataSource) -> T): T {
            return withNewDatabaseFrom(templateName = name, block = block)
        }
    }
}
