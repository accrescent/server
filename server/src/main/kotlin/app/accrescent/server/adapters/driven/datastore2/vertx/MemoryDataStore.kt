// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import app.accrescent.server.core.ThrowableError
import app.accrescent.server.core.causedBy
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreError
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreResult
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import io.vertx.core.Vertx
import io.vertx.jdbcclient.JDBCConnectOptions
import io.vertx.jdbcclient.JDBCPool
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.h2.api.ErrorCode
import org.h2.jdbcx.JdbcDataSource
import java.sql.SQLException
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

/**
 * A memory-backed application data store.
 *
 * Each instance of [MemoryDataStore] is backed by a separate in-memory database. The database is
 * destroyed when the JVM exits or when [close] is called.
 *
 * @param vertx the Vert.x object to attach the database pool to.
 */
class MemoryDataStore(vertx: Vertx) : DataStore {
    private val jdbcUrl = "jdbc:h2:mem:${Uuid.random()};DB_CLOSE_DELAY=-1"
    private val flyway = Flyway
        .configure()
        .dataSource(JdbcDataSource().apply { setURL(jdbcUrl) })
        .locations("db/migration/memory")
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .load()

    // According to https://www.h2database.com/html/advanced.html#transaction_isolation regarding
    // ANSI SERIALIZABLE transactions:
    //
    // > Note that this isolation level in H2 currently doesn't ensure equivalence of concurrent
    // > and serializable execution of transactions that perform write operations.
    //
    // More specifically, H2's SERIALIZABLE isolation level doesn't detect certain classes of
    // serialization anomalies  that a true serializable isolation level would, e.g., G2. Thus, to
    // achieve true serializable transactions, this pool holds only a single connection so that
    // transactions run one at a time. Obviously this approach has huge performance overhead, but
    // since MemoryDataStore is intended only for testing and development, that's fine.
    private val pool: Pool =
        JDBCPool.pool(vertx, JDBCConnectOptions().setJdbcUrl(jdbcUrl), PoolOptions().setMaxSize(1))

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
    ): DataStoreResult<Either<E, T>> = either {
        val connection = catch({ pool.awaitConnection() }) { raise(it.toDataStoreError()) }
        try {
            val transaction = catch({ connection.begin().coAwait() }) {
                raise(it.toDataStoreError())
            }
            val result = either { block(MemoryTransaction(connection)) }
            if (result.isRight()) {
                // Don't commit for a caller that was canceled while block ran
                currentCoroutineContext().ensureActive()
                catch({ transaction.commit().coAwait() }) { raise(it.toDataStoreError()) }
            }
            result
        } finally {
            // Closing the connection rolls back the transaction if it wasn't committed. There's
            // nothing to do if closing fails, so don't wait for it.
            connection.close()
        }
    }

    /**
     * Closes the in-memory database, destroying it.
     */
    fun close(): DataStoreResult<Unit> {
        // Execute SHUTDOWN as a batch query so we don't attempt to read the statement's update
        // count after executing it, which always fails since the database is destroyed then
        val shutdown = pool.preparedQuery("SHUTDOWN").executeBatch(listOf(Tuple.tuple()))
        shutdown.otherwiseEmpty().await()
        val close = pool.close()
        close.otherwiseEmpty().await()

        return when {
            shutdown.failed() -> shutdown.cause().toDataStoreError().left()
            close.failed() -> close.cause().toDataStoreError().left()
            else -> Unit.right()
        }
    }
}

/**
 * A [DataStore.Transaction] for a [MemoryDataStore].
 *
 * @property connection the connection the transaction is running on.
 */
private class MemoryTransaction(private val connection: SqlConnection) : DataStore.Transaction {
    override suspend fun createOrgWithOwner(
        organizationId: String,
        userId: String,
        externalUserId: ExternalUserId,
        createTime: OffsetDateTime
    ): DataStoreResult<Unit> = either {
        val githubUserId = when (externalUserId) {
            is ExternalUserId.Github -> externalUserId.userId
        }

        // The organization and its owner reference each other, so create the organization first,
        // create its owner next, and then set the organization's owner
        connection.executeSingleUpdate(
            "INSERT INTO organizations (id, create_time) VALUES (?, ?)",
            Tuple.of(organizationId, createTime),
        )
        connection.executeSingleUpdate(
            "INSERT INTO users (id, organization_id, create_time, github_user_id) VALUES (?, ?, ?, ?)",
            Tuple.of(userId, organizationId, createTime, githubUserId),
        )
        connection.executeSingleUpdate(
            "UPDATE organizations SET owner_user_id = ? WHERE id = ?",
            Tuple.of(userId, organizationId),
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
}

/**
 * Executes a query which should return at most one row.
 *
 * Shorthand for [executeOptionalQuery] using H2's error mapping.
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
 * Executes a query which should affect exactly one row.
 *
 * Shorthand for [executeSingleUpdate] using H2's error mapping.
 *
 * @param sql the SQL statement to execute.
 * @param params the statement's parameters.
 */
context(_: Raise<DataStoreError>)
private suspend fun SqlConnection.executeSingleUpdate(sql: String, params: Tuple) {
    executeSingleUpdate(sql, params, Throwable::toDataStoreError)
}

/**
 * The SQL states H2 reports for statements which would violate a constraint.
 */
private val CONSISTENCY_VIOLATION_SQL_STATES = setOf(
    ErrorCode.CHECK_CONSTRAINT_VIOLATED_1,
    ErrorCode.DUPLICATE_KEY_1,
    ErrorCode.NULL_NOT_ALLOWED,
    ErrorCode.REFERENTIAL_INTEGRITY_VIOLATED_CHILD_EXISTS_1,
    ErrorCode.REFERENTIAL_INTEGRITY_VIOLATED_PARENT_MISSING_1,
)
    .map(Int::toString)

/**
 * The SQL state H2 reports when it detects a deadlock.
 */
private const val SERIALIZATION_FAILURE_SQL_STATE = ErrorCode.DEADLOCK_1.toString()

/**
 * Converts this throwable into its corresponding [DataStoreError].
 */
private fun Throwable.toDataStoreError(): DataStoreError {
    val error = when ((this as? SQLException)?.sqlState) {
        in CONSISTENCY_VIOLATION_SQL_STATES -> DataStoreError.ConsistencyViolation()
        SERIALIZATION_FAILURE_SQL_STATE -> DataStoreError.SerializationFailure()
        else -> DataStoreError.Unknown()
    }

    return error.causedBy(ThrowableError(this))
}
