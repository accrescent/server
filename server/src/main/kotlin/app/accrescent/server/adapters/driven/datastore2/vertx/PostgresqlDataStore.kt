// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import app.accrescent.server.core.ThrowableError
import app.accrescent.server.core.causedBy
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.config.DataStoreConfig
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreError
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreResult
import app.accrescent.server.domain.ports.driven.datastore2.HasPermissionRequest
import app.accrescent.server.domain.ports.driven.datastore2.OrgApiView
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.right
import io.vertx.core.Vertx
import io.vertx.jdbcclient.JDBCPool
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLState
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * A PostgreSQL-backed application data store.
 *
 * @param vertx the Vert.x object to attach the database pool to.
 * @param config the configuration for connecting to the PostgreSQL database.
 */
class PostgresqlDataStore(vertx: Vertx, config: DataStoreConfig.Postgresql) : DataStore {
    private val dataSource = PGSimpleDataSource().apply {
        serverNames = arrayOf(config.host.value)
        portNumbers = intArrayOf(config.port.value.toInt())
        databaseName = config.database.value
        user = config.user.value
        password = config.password.value
        // Make SERIALIZABLE the default isolation level so that we don't need to set it in a
        // separate statement on each connection
        //
        // https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-DEFAULT-TRANSACTION-ISOLATION
        options = "-c default_transaction_isolation=serializable"
    }
    private val flyway = Flyway
        .configure()
        .dataSource(dataSource)
        .locations("db/migration/postgresql2")
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .load()
    private val pool: Pool = JDBCPool.pool(vertx, dataSource, PoolOptions())

    override fun migrateToHead(): DataStoreResult<Unit> {
        return try {
            flyway.migrate()
            Unit.right()
        } catch (e: FlywayException) {
            e.toDataStoreError().left()
        }
    }

    override suspend fun <T, E> runInTransaction(
        block: suspend Raise<E>.(DataStore.Transaction) -> T,
    ): DataStoreResult<Either<E, T>> {
        return pool.runInTransaction(Throwable::toDataStoreError) { connection ->
            block(PostgresqlTransaction(connection))
        }
    }

    /**
     * Closes the data store's connections to the database.
     */
    fun close(): DataStoreResult<Unit> {
        val close = pool.close()
        close.otherwiseEmpty().await()

        return if (close.failed()) {
            close.cause().toDataStoreError().left()
        } else {
            Unit.right()
        }
    }
}

/**
 * A [DataStore.Transaction] for a [PostgresqlDataStore].
 *
 * @property connection the connection the transaction is running on.
 */
private class PostgresqlTransaction(
    private val connection: SqlConnection,
) : DataStore.Transaction() {
    override suspend fun createOrgWithOwner(
        organizationId: String,
        userId: String,
        externalUserId: ExternalUserId,
        createTime: OffsetDateTime
    ): DataStoreResult<Unit> = either {
        val githubUserId = when (externalUserId) {
            is ExternalUserId.Github -> externalUserId.userId
        }

        connection.executeSingleUpdate(
            """
                WITH new_organization AS (
                    INSERT INTO organizations (id, owner_user_id, create_time) VALUES (?, ?, ?)
                )
                INSERT INTO users (id, organization_id, create_time, github_user_id)
                VALUES (?, ?, ?, ?)
            """.trimIndent(),
            Tuple.of(
                organizationId,
                userId,
                createTime,
                userId,
                organizationId,
                createTime,
                githubUserId,
            ),
        )
    }

    override suspend fun createSession(
        idHash: Sha256Hash,
        userId: String,
        createTime: OffsetDateTime,
        expireTime: OffsetDateTime
    ): DataStoreResult<Unit> = either {
        connection.executeSingleUpdate(
            "INSERT INTO sessions (id_hash, user_id, create_time, expire_time) VALUES (?, ?, ?, ?)",
            Tuple.of(idHash.digest().copyToByteArray(), userId, createTime, expireTime),
        )
    }

    override suspend fun findOrgApiViewById(
        organizationId: String,
    ): DataStoreResult<Option<OrgApiView>> = either {
        connection.executeOptionalQuery(
            "SELECT id FROM organizations WHERE id = ?",
            Tuple.of(organizationId),
        )
            .map {
                // Apps and app drafts aren't stored yet, so organizations never have any
                OrgApiView(
                    id = it.requireValue<String>(0).bind(),
                    publishedAppCount = 0u,
                    activeAppDraftCount = 0u,
                )
            }
    }

    override suspend fun findOrgIdByOwnerUserId(
        userId: String,
    ): DataStoreResult<Option<String>> = either {
        connection.executeOptionalQuery(
            "SELECT id FROM organizations WHERE owner_user_id = ?",
            Tuple.of(userId),
        )
            .map { it.requireValue<String>(0).bind() }
    }

    override suspend fun findUserIdByActiveSessionIdHash(
        sessionIdHash: Sha256Hash,
        currentTime: OffsetDateTime,
    ): DataStoreResult<Option<String>> = either {
        connection.executeOptionalQuery(
            "SELECT user_id FROM sessions WHERE id_hash = ? AND expire_time > ?",
            Tuple.of(sessionIdHash.digest().copyToByteArray(), currentTime),
        )
            .map { it.requireValue<String>(0).bind() }
    }

    override suspend fun findUserIdByExternalUserId(
        externalUserId: ExternalUserId,
    ): DataStoreResult<Option<String>> = either {
        val githubUserId = when (externalUserId) {
            is ExternalUserId.Github -> externalUserId.userId
        }

        connection.executeOptionalQuery(
            "SELECT id FROM users WHERE github_user_id = ?",
            Tuple.of(githubUserId),
        )
            .map { it.requireValue<String>(0).bind() }
    }

    override suspend fun hasPermission(
        request: HasPermissionRequest,
    ): DataStoreResult<Boolean> = either {
        val sql = when (request) {
            is HasPermissionRequest.ViewOrganization ->
                "SELECT EXISTS(SELECT 1 FROM organizations WHERE id = ? AND owner_user_id = ?)"
        }

        connection
            .executeSingleQuery(sql, Tuple.of(request.resourceId, request.subjectId))
            .requireValue<Boolean>(0)
            .bind()
    }
}

/**
 * Executes a query which should return at most one row.
 *
 * Shorthand for [executeOptionalQuery] using PostgreSQL's error mapping.
 *
 * @param sql the SQL query to execute.
 * @param params the query's parameters.
 * @return the row returned by the query, or [None] if it returned no rows.
 */
context(_: Raise<DataStoreError>)
private suspend fun SqlConnection.executeOptionalQuery(sql: String, params: Tuple): Option<Row> {
    return executeOptionalQuery(sql, params, Throwable::toDataStoreError)
}

/**
 * Executes a query which should return exactly one row.
 *
 * Shorthand for [executeSingleQuery] using PostgreSQL's error mapping.
 *
 * @param sql the SQL query to execute.
 * @param params the query's parameters.
 * @return the row returned by the query.
 */
context(_: Raise<DataStoreError>)
private suspend fun SqlConnection.executeSingleQuery(sql: String, params: Tuple): Row {
    return executeSingleQuery(sql, params, Throwable::toDataStoreError)
}

/**
 * Executes a query which should affect exactly one row.
 *
 * Shorthand for [executeSingleUpdate] using PostgreSQL's error mapping.
 *
 * @param sql the SQL statement to execute.
 * @param params the statement's parameters.
 */
context(_: Raise<DataStoreError>)
private suspend fun SqlConnection.executeSingleUpdate(sql: String, params: Tuple) {
    executeSingleUpdate(sql, params, Throwable::toDataStoreError)
}

/**
 * The SQL states PostgreSQL reports for statements which would violate a constraint.
 */
private val CONSISTENCY_VIOLATION_SQL_STATES = setOf(
    PSQLState.CHECK_VIOLATION,
    PSQLState.FOREIGN_KEY_VIOLATION,
    PSQLState.NOT_NULL_VIOLATION,
    PSQLState.UNIQUE_VIOLATION,
)
    .map(PSQLState::getState)

/**
 * The SQL states PostgreSQL reports when a transaction can't be serialized with other concurrent
 * transactions.
 */
private val SERIALIZATION_FAILURE_SQL_STATES = setOf(
    PSQLState.DEADLOCK_DETECTED,
    PSQLState.SERIALIZATION_FAILURE,
)
    .map(PSQLState::getState)

/**
 * Converts this throwable into its corresponding [DataStoreError].
 */
private fun Throwable.toDataStoreError(): DataStoreError {
    val error = when ((this as? SQLException)?.sqlState) {
        in CONSISTENCY_VIOLATION_SQL_STATES -> DataStoreError.ConsistencyViolation()
        in SERIALIZATION_FAILURE_SQL_STATES -> DataStoreError.SerializationFailure()
        else -> DataStoreError.Unknown()
    }

    return error.causedBy(ThrowableError(this))
}
