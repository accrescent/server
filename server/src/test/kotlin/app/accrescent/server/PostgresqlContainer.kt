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
    // The minimum supported version of PostgreSQL is 18, so we test against that.
    private val POSTGRESQL_IMAGE = DockerImageName
        .parse(
            "postgres:18@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4"
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
     * Runs a suspending lambda with the connection details for a new, empty database.
     *
     * Each call creates a new database, so the data accessible through the provided [Database] is
     * not shared with any other caller. Attempts to use the [Database] outside of [block]'s scope
     * result in undefined behavior.
     *
     * @param block the lambda to run with access to the new database.
     * @return the return value of [block].
     */
    suspend fun <T> withNewDatabaseSuspending(block: suspend (Database) -> T): T {
        val databaseName = "test_${UUID.randomUUID()}"

        executeOnDefaultDatabase("CREATE DATABASE \"$databaseName\"")

        return AutoCloseable { executeOnDefaultDatabase("DROP DATABASE \"$databaseName\"") }
            .use { block(databaseFor(databaseName)) }
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
     * Constructs a [Database] for a given database.
     *
     * @param databaseName the name of the database to construct a [Database] for.
     * @return a [Database] for the database named by [databaseName].
     */
    private fun databaseFor(databaseName: String): Database {
        return Database(
            host = container.host,
            port = container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            name = databaseName,
            username = container.username,
            password = container.password,
        )
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
     * The connection details of a database in the container.
     *
     * @property host the host the container's PostgreSQL server is reachable at.
     * @property port the port the container's PostgreSQL server is reachable at.
     * @property name the name of the database.
     * @property username the name of the user with access to the database.
     * @property password the password of the user with access to the database.
     */
    data class Database(
        val host: String,
        val port: Int,
        val name: String,
        val username: String,
        val password: String,
    )

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
