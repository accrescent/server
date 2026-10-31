// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore2.vertx

import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.PositiveInt
import app.accrescent.server.core.ThrowableError
import app.accrescent.server.core.causedBy
import app.accrescent.server.domain.appstore.AppName
import app.accrescent.server.domain.appstore.AppShortDescription
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.datastore2.AppDraftListingTextApiView
import app.accrescent.server.domain.ports.driven.datastore2.AppDraftSummaryApiView
import app.accrescent.server.domain.ports.driven.datastore2.AppDraftSummarySortKey
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreError
import app.accrescent.server.domain.ports.driven.datastore2.DataStoreResult
import app.accrescent.server.domain.ports.driven.datastore2.HasPermissionRequest
import app.accrescent.server.domain.ports.driven.datastore2.OrgApiView
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.right
import io.vertx.core.Vertx
import io.vertx.jdbcclient.JDBCConnectOptions
import io.vertx.jdbcclient.JDBCPool
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
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
    ): DataStoreResult<Either<E, T>> {
        return pool.runInTransaction(Throwable::toDataStoreError) { connection ->
            block(MemoryTransaction(connection))
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

    private companion object {
        /**
         * Returns the number of Unicode code points in a string.
         *
         * @param s the string to count the Unicode code points of.
         * @return the number of Unicode code points in [s], or null if [s] is null.
         */
        // Used in data store SQL
        @Suppress("UNUSED")
        @JvmStatic
        fun codePointLength(s: String?): Int? {
            return s?.let { it.codePointCount(0, it.length) }
        }
    }
}

/**
 * A [DataStore.Transaction] for a [MemoryDataStore].
 *
 * @property connection the connection the transaction is running on.
 */
private class MemoryTransaction(private val connection: SqlConnection) : DataStore.Transaction() {
    override suspend fun countAppDraftsInOrg(
        organizationId: String,
    ): DataStoreResult<NonNegativeInt> = either {
        connection
            .executeSingleQuery(
                "SELECT CAST(COUNT(*) AS INTEGER) FROM app_drafts WHERE organization_id = ?",
                Tuple.of(organizationId),
            )
            .requireNonNegativeInt(0)
    }

    override suspend fun createAppDraft(
        appDraftId: String,
        organizationId: String,
        createTime: OffsetDateTime,
    ): DataStoreResult<Unit> = either {
        connection.executeSingleUpdate(
            "INSERT INTO app_drafts (id, organization_id, create_time) VALUES (?, ?, ?)",
            Tuple.of(appDraftId, organizationId, createTime),
        )
    }

    override suspend fun createAppDraftListingText(
        appDraftId: String,
        name: AppName,
        shortDescription: AppShortDescription,
    ): DataStoreResult<Option<String>> = either {
        connection.executeOptionalQuery(
            """
            SELECT etag
            FROM FINAL TABLE (
                INSERT INTO app_draft_listing_texts (app_draft_id, name, short_description)
                SELECT ?, ?, ?
                WHERE NOT EXISTS(SELECT 1 FROM app_draft_listing_texts WHERE app_draft_id = ?)
            )
            """.trimIndent(),
            Tuple.of(appDraftId, name.value, shortDescription.value, appDraftId),
        )
            .map { it.requireValue<String>(0) }
    }

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

    override suspend fun deleteActiveSession(
        idHash: Sha256Hash,
        currentTime: OffsetDateTime,
    ): DataStoreResult<Boolean> = either {
        connection.executeOptionalUpdate(
            "DELETE FROM sessions WHERE id_hash = ? AND expire_time > ?",
            Tuple.of(idHash.digest().copyToByteArray(), currentTime),
        )
    }

    override suspend fun deleteAppDraft(appDraftId: String): DataStoreResult<Unit> = either {
        connection.executeSingleUpdate("DELETE FROM app_drafts WHERE id = ?", Tuple.of(appDraftId))
    }

    override suspend fun findAppDraftListingTextApiViewByAppDraftId(
        appDraftId: String,
    ): DataStoreResult<Option<AppDraftListingTextApiView>> = either {
        connection.executeOptionalQuery(
            "SELECT name, short_description, etag FROM app_draft_listing_texts WHERE app_draft_id = ?",
            Tuple.of(appDraftId),
        )
            .map {
                AppDraftListingTextApiView(
                    name = it.requireValue<String>(0),
                    shortDescription = it.requireValue<String>(1),
                    etag = it.requireValue<String>(2),
                )
            }
    }

    override suspend fun findOrgApiViewById(
        organizationId: String,
    ): DataStoreResult<Option<OrgApiView>> = either {
        connection.executeOptionalQuery(
            """
                SELECT
                    id,
                    (
                        SELECT CAST(COUNT(*) AS INTEGER)
                        FROM app_drafts
                        WHERE organization_id = organizations.id
                    )
                FROM organizations
                WHERE id = ?
            """.trimIndent(),
            Tuple.of(organizationId),
        )
            .map {
                OrgApiView(
                    id = it.requireValue<String>(0),
                    // Apps aren't stored yet, so organizations never have any published
                    publishedAppCount = NonNegativeInt.MIN_VALUE,
                    // App drafts can't be reviewed yet, so all of them are active
                    activeAppDraftCount = it.requireNonNegativeInt(1),
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
            .map { it.requireValue<String>(0) }
    }

    override suspend fun findUserIdByActiveSessionIdHash(
        sessionIdHash: Sha256Hash,
        currentTime: OffsetDateTime,
    ): DataStoreResult<Option<String>> = either {
        connection.executeOptionalQuery(
            "SELECT user_id FROM sessions WHERE id_hash = ? AND expire_time > ?",
            Tuple.of(sessionIdHash.digest().copyToByteArray(), currentTime),
        )
            .map { it.requireValue<String>(0) }
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
            .map { it.requireValue<String>(0) }
    }

    override suspend fun hasPermission(
        request: HasPermissionRequest,
    ): DataStoreResult<Boolean> = either {
        val sql = when (request) {
            is HasPermissionRequest.CreateAppDraft,
            is HasPermissionRequest.ViewAppDrafts,
            is HasPermissionRequest.ViewOrganization ->
                "SELECT EXISTS(SELECT 1 FROM organizations WHERE id = ? AND owner_user_id = ?)"

            is HasPermissionRequest.DeleteAppDraft,
            is HasPermissionRequest.EditAppDraftListing,
            is HasPermissionRequest.ViewAppDraft ->
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM app_drafts
                    JOIN organizations ON organizations.id = app_drafts.organization_id
                    WHERE app_drafts.id = ? AND organizations.owner_user_id = ?
                )
                """.trimIndent()
        }

        connection
            .executeSingleQuery(sql, Tuple.of(request.resourceId, request.subjectId))
            .requireValue<Boolean>(0)
    }

    override suspend fun listAppDraftSummaryApiViewsInOrg(
        organizationId: String,
        maxResults: PositiveInt,
        after: Option<AppDraftSummarySortKey>,
    ): DataStoreResult<List<AppDraftSummaryApiView>> = either {
        val rows = when (after) {
            is None -> connection.executeQuery(
                """
                    SELECT id, create_time
                    FROM app_drafts
                    WHERE organization_id = ?
                    ORDER BY create_time DESC, id DESC
                    LIMIT ?
                """.trimIndent(),
                Tuple.of(organizationId, maxResults.value),
            )

            is Some -> connection.executeQuery(
                """
                    SELECT id, create_time
                    FROM app_drafts
                    WHERE organization_id = ? AND (create_time, id) < (?, ?)
                    ORDER BY create_time DESC, id DESC
                    LIMIT ?
                """.trimIndent(),
                Tuple.of(organizationId, after.value.createTime, after.value.id, maxResults.value),
            )
        }

        rows.map {
            AppDraftSummaryApiView(
                id = it.requireValue<String>(0),
                createTime = it.requireValue<OffsetDateTime>(1),
            )
        }
    }

    override suspend fun updateAppDraftListingText(
        appDraftId: String,
        etag: String,
        name: Option<AppName>,
        shortDescription: Option<AppShortDescription>,
    ): DataStoreResult<Option<String>> = either {
        connection.executeOptionalQuery(
            """
            SELECT etag
            FROM FINAL TABLE (
                UPDATE app_draft_listing_texts
                SET name = COALESCE(?, name), short_description = COALESCE(?, short_description)
                WHERE app_draft_id = ?
                AND etag = ?
            )
            """.trimIndent(),
            Tuple.of(
                name.map { it.value }.getOrNull(),
                shortDescription.map { it.value }.getOrNull(),
                appDraftId,
                etag,
            ),
        )
            .map { it.requireValue<String>(0) }
    }
}

/**
 * Executes a query which may return any number of rows.
 *
 * Shorthand for [executeQuery] using H2's error mapping.
 *
 * @param sql the SQL query to execute.
 * @param params the query's parameters.
 * @return the rows returned by the query.
 */
context(_: Raise<DataStoreError>)
private suspend fun SqlConnection.executeQuery(sql: String, params: Tuple): List<Row> {
    return executeQuery(sql, params, Throwable::toDataStoreError)
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
 * Executes a query which should return exactly one row.
 *
 * Shorthand for [executeSingleQuery] using H2's error mapping.
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
 * Executes a query which should affect at most one row.
 *
 * Shorthand for [executeOptionalUpdate] using H2's error mapping.
 *
 * @param sql the SQL statement to execute.
 * @param params the statement's parameters.
 * @return whether the statement affected a row.
 */
context(_: Raise<DataStoreError>)
private suspend fun SqlConnection.executeOptionalUpdate(sql: String, params: Tuple): Boolean {
    return executeOptionalUpdate(sql, params, Throwable::toDataStoreError)
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
