// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.datastore.jdbc

import app.accrescent.server.core.Bytes
import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.domain.android.AndroidManifest
import app.accrescent.server.domain.android.ApkParseError
import app.accrescent.server.domain.android.ApkSetParseError
import app.accrescent.server.domain.android.ApplicationId
import app.accrescent.server.domain.android.NameAttribute
import app.accrescent.server.domain.android.SdkVersion
import app.accrescent.server.domain.android.VersionCode
import app.accrescent.server.domain.android.VersionName
import app.accrescent.server.domain.appstore.ListingLanguage
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.datastore.App
import app.accrescent.server.domain.ports.driven.datastore.AppDraftApiView
import app.accrescent.server.domain.ports.driven.datastore.AppDraftListingApiView
import app.accrescent.server.domain.ports.driven.datastore.AppDraftListingIconUploadProcessingResult
import app.accrescent.server.domain.ports.driven.datastore.AppDraftUploadProcessingError
import app.accrescent.server.domain.ports.driven.datastore.AppListing
import app.accrescent.server.domain.ports.driven.datastore.AppPackage
import app.accrescent.server.domain.ports.driven.datastore.AppPackageApiView
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.datastore.DataStore.AppDraftRepository
import app.accrescent.server.domain.ports.driven.datastore.DataStore.AppPackageRepository
import app.accrescent.server.domain.ports.driven.datastore.DataStore.AppRepository
import app.accrescent.server.domain.ports.driven.datastore.DataStore.AuthorizationRepository
import app.accrescent.server.domain.ports.driven.datastore.DataStore.ExternalBlobRepository
import app.accrescent.server.domain.ports.driven.datastore.DataStore.OrganizationRepository
import app.accrescent.server.domain.ports.driven.datastore.DataStore.SessionRepository
import app.accrescent.server.domain.ports.driven.datastore.DataStore.Transaction
import app.accrescent.server.domain.ports.driven.datastore.DataStore.UserRepository
import app.accrescent.server.domain.ports.driven.datastore.DataStoreError
import app.accrescent.server.domain.ports.driven.datastore.DataStoreResult
import app.accrescent.server.domain.ports.driven.datastore.ExternalBlob
import app.accrescent.server.domain.ports.driven.datastore.HasPermissionRequest
import app.accrescent.server.domain.ports.driven.datastore.PendingAppDraftListingIconUpload
import app.accrescent.server.domain.ports.driven.datastore.PendingAppDraftUpload
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.right
import arrow.core.some
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.h2.api.ErrorCode
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Runs the given lambda of JDBC code, converting [SQLException]s from this [DataStore]
 * implementation into appropriate typed errors.
 *
 * @param block the lambda to run in the catching context.
 * @return the return value of [block], or a [DataStoreError] if [block] throws a [SQLException].
 */
private inline fun <T> runCatchingSql(block: Raise<DataStoreError>.() -> T): DataStoreResult<T> {
    return either {
        try {
            block()
        } catch (e: SQLException) {
            raise(e.toDataStoreError())
        }
    }
}

private fun SQLException.toDataStoreError(): DataStoreError {
    return when (sqlState) {
        ErrorCode.CHECK_CONSTRAINT_VIOLATED_1.toString(),
        ErrorCode.DUPLICATE_KEY_1.toString(),
        ErrorCode.REFERENTIAL_INTEGRITY_VIOLATED_CHILD_EXISTS_1.toString(),
        ErrorCode.REFERENTIAL_INTEGRITY_VIOLATED_PARENT_MISSING_1.toString() ->
            DataStoreError.ConsistencyViolation

        ErrorCode.DEADLOCK_1.toString() -> DataStoreError.SerializationFailure
        else -> DataStoreError.Unknown
    }
}

class InMemoryDataStore(randomSource: RandomSource) : DataStore(randomSource), AutoCloseable {
    private val dataSource: DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }

    // According to https://www.h2database.com/html/advanced.html#transaction_isolation regarding
    // ANSI SERIALIZABLE transactions:
    //
    // > Note that this isolation level in H2 currently doesn't ensure equivalence of concurrent
    // > and serializable execution of transactions that perform write operations.
    //
    // More specifically, H2's SERIALIZABLE isolation level doesn't detect certain classes of
    // serialization anomalies that a true serializable isolation level would, e.g., G2. Thus, to
    // achieve true serializable transactions, this DataStore implementation holds an exclusive lock
    // to run database transactions. Obviously this approach has huge performance overhead, but
    // since InMemoryDataStore is intended only for testing and development, that's fine.
    private val dbLock = Any()
    private val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("db/migration/inmemory")
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .load()

    override fun migrateToHead(): DataStoreResult<Unit> = synchronized(dbLock) {
        try {
            flyway.migrate()
            Unit.right()
        } catch (_: FlywayException) {
            DataStoreError.Unknown.left()
        }
    }

    override fun <T, E> runInTransaction(
        block: Raise<E>.(Transaction) -> T,
    ): DataStoreResult<Either<E, T>> = synchronized(dbLock) {
        try {
            dataSource.connection.apply { autoCommit = false }
        } catch (e: SQLException) {
            return@synchronized e.toDataStoreError().left()
        }.use { connection ->
            val result = try {
                either { block(InMemoryTransaction(connection)) }.right()
            } catch (t: Throwable) {
                t.left()
            }

            when (result) {
                // Block threw, so attempt to roll back, reporting a failed rollback as a suppressed
                // exception. Rethrow the original throwable unconditionally so the caller can
                // handle it.
                is Either.Left -> {
                    try {
                        connection.rollback()
                    } catch (e: SQLException) {
                        throw result.value.apply { addSuppressed(e) }
                    }
                    throw result.value
                }

                // Block returned
                is Either.Right -> when (val blockResult = result.value) {
                    // Block returned error, so attempt to roll back, returning a rollback error if
                    // rollback fails
                    is Either.Left -> {
                        try {
                            connection.rollback()
                            blockResult.right()
                        } catch (e: SQLException) {
                            e.toDataStoreError().left()
                        }
                    }

                    // Block succeeded, so attempt to commit, returning the block result if
                    // successful. If there's a commit error, attempt to roll back and return the
                    // commit error. If rollback fails, return both the commit error and the
                    // rollback error.
                    is Either.Right -> {
                        try {
                            connection.commit()
                            blockResult.right()
                        } catch (commitException: SQLException) {
                            try {
                                connection.rollback()
                                commitException.toDataStoreError().left()
                            } catch (rollbackException: SQLException) {
                                DataStoreError
                                    .RollbackErrorOnCommit(
                                        rollbackError = rollbackException.toDataStoreError(),
                                        commitError = commitException.toDataStoreError(),
                                    )
                                    .left()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(dbLock) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SHUTDOWN") }
            }
        }
    }

    companion object {
        // Used by nonempty_can_text definition
        @Suppress("UNUSED")
        @JvmStatic
        fun codePointLength(s: String?): Int? {
            return s?.codePointCount(0, s.length)
        }

        // Used by nonempty_can_text definition
        @Suppress("UNUSED")
        @JvmStatic
        fun isNfcNormalized(s: String?): Boolean? {
            return s?.let { Normalizer.isNormalized(it, Normalizer.Form.NFC) }
        }

        // Used by nonempty_can_text definition
        @Suppress("UNUSED")
        @JvmStatic
        fun isUnicodeAssigned(s: String?): Boolean? {
            return s?.codePoints()?.allMatch { Character.getType(it) != Character.UNASSIGNED.toInt() }
        }
    }
}

private class InMemoryTransaction(connection: Connection) : Transaction {
    override val appDrafts = InMemoryAppDraftRepository(connection)
    override val appPackages = InMemoryAppPackageRepository(connection)
    override val apps = InMemoryAppRepository(connection)
    override val authz = InMemoryAuthorizationRepository(connection)
    override val externalBlobs = InMemoryExternalBlobRepository(connection)
    override val organizations = InMemoryOrganizationRepository(connection)
    override val sessions = InMemorySessionRepository(connection)
    override val users = InMemoryUserRepository(connection)
}

private class InMemoryAppDraftRepository(
    private val connection: Connection,
) : AppDraftRepository() {
    override fun countActiveInOrganization(organizationId: String): DataStoreResult<ULong> =
        runCatchingSql {
            val sql = "SELECT COUNT(1) FROM app_drafts WHERE app_drafts.organization_id = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, organizationId)
                stmt.executeQuery().use { rs -> rs.getSelectCountResult().bind() }
            }
        }

    override fun completePendingUpload(
        pendingUploadId: String,
        error: AppDraftUploadProcessingError,
        blobDeleteTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        // Mark the blob for deletion and make it release its owning pending app draft upload
        val releaseSql = """
            UPDATE external_blobs
            SET status = 'deleted', delete_time = ?, pending_app_draft_upload_id = NULL
            WHERE pending_app_draft_upload_id = ?
            """.trimIndent()
        connection.prepareStatement(releaseSql).use { stmt ->
            stmt.setObject(1, blobDeleteTime)
            stmt.setString(2, pendingUploadId)
            stmt.executeMultiUpdate().bind()
        }

        // Update the pending app draft upload result
        val updateResultSql = """
            UPDATE pending_app_draft_uploads
            SET processing_result = ?, external_blob_id = NULL
            WHERE id = ? AND processing_result IS NULL
        """.trimIndent()
        connection.prepareStatement(updateResultSql).use { stmt ->
            stmt.setString(1, error.toColumnValue())
            stmt.setString(2, pendingUploadId)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun create(
        organizationId: String,
        appDraftId: String,
        createTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = """
            INSERT INTO app_drafts (id, organization_id, create_time)
            VALUES (?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, appDraftId)
            stmt.setString(2, organizationId)
            stmt.setObject(3, createTime)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun createListing(
        id: String,
        appDraftId: String,
        language: ListingLanguage,
        name: String,
        shortDescription: String,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = """
            INSERT INTO app_draft_listings (id, app_draft_id, language, name, short_description)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.setString(2, appDraftId)
            stmt.setString(3, language.languageTag())
            stmt.setString(4, name)
            stmt.setString(5, shortDescription)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun deleteById(
        id: String,
        blobDeleteTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        // Release every blob owned by the app draft's package, pending upload and listing icon
        // uploads
        val releaseSql = """
            UPDATE external_blobs
            SET status = 'deleted',
                delete_time = ?,
                app_package_id = NULL,
                pending_app_draft_upload_id = NULL,
                pending_app_draft_listing_icon_upload_id = NULL
            WHERE app_package_id IN (SELECT id FROM app_packages WHERE app_draft_id = ?)
               OR pending_app_draft_upload_id IN (
                      SELECT id FROM pending_app_draft_uploads WHERE app_draft_id = ?
                  )
               OR pending_app_draft_listing_icon_upload_id IN (
                      SELECT icon_uploads.id
                      FROM pending_app_draft_listing_icon_uploads icon_uploads
                      JOIN app_draft_listings
                      ON app_draft_listings.id = icon_uploads.app_draft_listing_id
                      WHERE app_draft_listings.app_draft_id = ?
                  )
        """.trimIndent()
        connection.prepareStatement(releaseSql).use { stmt ->
            stmt.setObject(1, blobDeleteTime)
            for (parameterIndex in 2..4) {
                stmt.setString(parameterIndex, id)
            }
            stmt.executeMultiUpdate().bind()
        }

        // Null out the circular foreign keys to the listing and package before deleting so that the
        // cascade on app_draft_listings.app_draft_id and the package delete below can proceed
        // without a foreign key conflict. The submit time goes with them, since a submitted app
        // draft is required to name both.
        val clearSql = """
            UPDATE app_drafts
            SET default_app_draft_listing_id = NULL, app_package_id = NULL, submit_time = NULL
            WHERE id = ?
        """.trimIndent()
        connection.prepareStatement(clearSql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeSingleUpdate().bind()
        }

        // Every table which can own an external blob is deleted from explicitly, since none of them
        // cascade; only the app draft's listings are left to the cascade from app_drafts.
        val deleteIconUploadsSql = """
            DELETE FROM pending_app_draft_listing_icon_uploads
            WHERE app_draft_listing_id IN (SELECT id FROM app_draft_listings WHERE app_draft_id = ?)
        """.trimIndent()
        connection.prepareStatement(deleteIconUploadsSql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeMultiUpdate().bind()
        }
        val deleteUploadsSql = "DELETE FROM pending_app_draft_uploads WHERE app_draft_id = ?"
        connection.prepareStatement(deleteUploadsSql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeMultiUpdate().bind()
        }
        connection.prepareStatement("DELETE FROM app_packages WHERE app_draft_id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeMultiUpdate().bind()
        }
        connection.prepareStatement("DELETE FROM app_drafts WHERE id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun deleteListingById(
        id: String,
        blobDeleteTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        releaseListingIconBlob(connection, id, blobDeleteTime).bind()

        val deleteIconUploadSql =
            "DELETE FROM pending_app_draft_listing_icon_uploads WHERE app_draft_listing_id = ?"
        connection.prepareStatement(deleteIconUploadSql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeMultiUpdate().bind()
        }
        connection.prepareStatement("DELETE FROM app_draft_listings WHERE id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun deletePendingListingIconUploadByListingId(
        appDraftListingId: String,
        blobDeleteTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        releaseListingIconBlob(connection, appDraftListingId, blobDeleteTime).bind()

        val sql = "DELETE FROM pending_app_draft_listing_icon_uploads WHERE app_draft_listing_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, appDraftListingId)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun deletePendingUploadByAppDraftId(
        appDraftId: String,
        blobDeleteTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        val releaseSql = """
            UPDATE external_blobs
            SET status = 'deleted', delete_time = ?, pending_app_draft_upload_id = NULL
            WHERE pending_app_draft_upload_id IN (
                SELECT id FROM pending_app_draft_uploads WHERE app_draft_id = ?
            )
        """.trimIndent()
        connection.prepareStatement(releaseSql).use { stmt ->
            stmt.setObject(1, blobDeleteTime)
            stmt.setString(2, appDraftId)
            stmt.executeMultiUpdate().bind()
        }

        val sql = "DELETE FROM pending_app_draft_uploads WHERE app_draft_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, appDraftId)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun existsSubmittedForAppId(appId: ApplicationId): DataStoreResult<Boolean> =
        runCatchingSql {
            val sql = """
                SELECT EXISTS(
                    SELECT 1
                    FROM app_drafts
                    JOIN app_packages
                    ON app_packages.id = app_drafts.app_package_id
                    WHERE app_drafts.submit_time IS NOT NULL
                    AND app_packages.app_id = ?
                )
            """.trimIndent()
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, appId.value)
                stmt.executeQuery().use { rs -> rs.getSelectExistsResult().bind() }
            }
        }

    override fun findApiViewById(
        id: String,
    ): DataStoreResult<Option<AppDraftApiView>> = runCatchingSql {
        val sql = """
            SELECT
                app_drafts.id,
                app_drafts.create_time,
                app_drafts.default_app_draft_listing_id,
                app_drafts.submit_time,
                app_packages.app_id,
                app_packages.version_code,
                app_packages.version_name,
                app_packages.target_sdk
            FROM app_drafts
            LEFT JOIN app_packages
            ON app_packages.id = app_drafts.app_package_id
            WHERE app_drafts.id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                Some(rs.readAppDraftApiView().bind())
            }
        }
    }

    override fun findApiViewsForOrganizationAndUserByQuery(
        organizationId: String,
        userId: String,
        maxResults: NonNegativeInt,
        afterAppDraftId: Option<String>,
    ): DataStoreResult<List<AppDraftApiView>> = runCatchingSql {
        val sql = """
            SELECT
                app_drafts.id,
                app_drafts.create_time,
                app_drafts.default_app_draft_listing_id,
                app_drafts.submit_time,
                app_packages.app_id,
                app_packages.version_code,
                app_packages.version_name,
                app_packages.target_sdk
            FROM app_drafts
            JOIN organizations
            ON organizations.id = app_drafts.organization_id
            LEFT JOIN app_packages
            ON app_packages.id = app_drafts.app_package_id
            WHERE app_drafts.organization_id = ?
            AND organizations.owner_user_id = ?
            AND (? IS NULL OR app_drafts.id > ?)
            ORDER BY app_drafts.id
            LIMIT ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, organizationId)
            stmt.setString(2, userId)
            stmt.setString(3, afterAppDraftId.getOrNull())
            stmt.setString(4, afterAppDraftId.getOrNull())
            stmt.setInt(5, maxResults.value)
            stmt.executeQuery().use { rs ->
                val appDrafts = mutableListOf<AppDraftApiView>()
                while (rs.next()) {
                    appDrafts.add(rs.readAppDraftApiView().bind())
                }
                appDrafts
            }
        }
    }

    override fun findListingApiViewById(
        id: String,
    ): DataStoreResult<Option<AppDraftListingApiView>> = runCatchingSql {
        val sql = """
            SELECT id, app_draft_id, language, name, short_description
            FROM app_draft_listings
            WHERE id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                Some(rs.readAppDraftListingApiView().bind())
            }
        }
    }

    override fun findListingApiViewsForAppDraftAndUserByQuery(
        appDraftId: String,
        userId: String,
        maxResults: NonNegativeInt,
        afterLanguage: Option<ListingLanguage>,
    ): DataStoreResult<List<AppDraftListingApiView>> = runCatchingSql {
        val sql = """
            SELECT
                app_draft_listings.id,
                app_draft_listings.app_draft_id,
                app_draft_listings.language,
                app_draft_listings.name,
                app_draft_listings.short_description
            FROM app_draft_listings
            JOIN app_drafts
            ON app_drafts.id = app_draft_listings.app_draft_id
            JOIN organizations
            ON organizations.id = app_drafts.organization_id
            WHERE app_draft_listings.app_draft_id = ?
            AND organizations.owner_user_id = ?
            AND (? IS NULL OR app_draft_listings.language > ?)
            ORDER BY app_draft_listings.language
            LIMIT ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, appDraftId)
            stmt.setString(2, userId)
            stmt.setString(3, afterLanguage.map { it.languageTag() }.getOrNull())
            stmt.setString(4, afterLanguage.map { it.languageTag() }.getOrNull())
            stmt.setInt(5, maxResults.value)
            stmt.executeQuery().use { rs ->
                val listings = mutableListOf<AppDraftListingApiView>()
                while (rs.next()) {
                    listings.add(rs.readAppDraftListingApiView().bind())
                }
                listings
            }
        }
    }

    override fun findPendingListingIconUploadByObjectKey(
        objectKey: String,
    ): DataStoreResult<Option<PendingAppDraftListingIconUpload>> = runCatchingSql {
        val sql = """
            SELECT
                id,
                app_draft_listing_id,
                external_blob_id,
                object_key,
                create_time,
                processing_result
            FROM pending_app_draft_listing_icon_uploads
            WHERE object_key = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, objectKey)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                val id = rs.requireString("id").bind()
                val appDraftListingId = rs.requireString("app_draft_listing_id").bind()
                val objectKey = rs.requireString("object_key").bind()
                val createTime = rs.requireObject<OffsetDateTime>("create_time").bind()

                Some(
                    when (val processingResult = rs.getSafeString("processing_result")) {
                        None -> PendingAppDraftListingIconUpload.Incomplete(
                            id = id,
                            appDraftListingId = appDraftListingId,
                            objectKey = objectKey,
                            createTime = createTime,
                            externalBlobId = rs.requireString("external_blob_id").bind(),
                        )

                        is Some -> PendingAppDraftListingIconUpload.Completed(
                            id = id,
                            appDraftListingId = appDraftListingId,
                            objectKey = objectKey,
                            createTime = createTime,
                            result = iconProcessingResultFromColumnValue(processingResult.value)
                                .toEitherBind { DataStoreError.IllegalState },
                        )
                    },
                )
            }
        }
    }

    override fun findPendingUploadByObjectKey(
        objectKey: String,
    ): DataStoreResult<Option<PendingAppDraftUpload>> = runCatchingSql {
        val sql = """
            SELECT id, app_draft_id, external_blob_id, object_key, create_time, processing_result
            FROM pending_app_draft_uploads
            WHERE object_key = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, objectKey)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                val id = rs.requireString("id").bind()
                val appDraftId = rs.requireString("app_draft_id").bind()
                val objectKey = rs.requireString("object_key").bind()
                val createTime = rs.requireObject<OffsetDateTime>("create_time").bind()

                Some(
                    when (val processingResult = rs.getSafeString("processing_result")) {
                        None -> PendingAppDraftUpload.Incomplete(
                            id = id,
                            appDraftId = appDraftId,
                            objectKey = objectKey,
                            createTime = createTime,
                            externalBlobId = rs.requireString("external_blob_id").bind(),
                        )

                        is Some -> PendingAppDraftUpload.Completed(
                            id = id,
                            appDraftId = appDraftId,
                            objectKey = objectKey,
                            createTime = createTime,
                            result = processingResultFromColumnValue(processingResult.value)
                                .toEitherBind { DataStoreError.IllegalState },
                        )
                    },
                )
            }
        }
    }

    override fun hasDefaultListing(id: String): DataStoreResult<Boolean> = runCatchingSql {
        val sql = "SELECT default_app_draft_listing_id IS NOT NULL FROM app_drafts WHERE id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) raise(DataStoreError.EntityNotFound)

                rs.requireBoolean(1).bind()
            }
        }
    }

    override fun isSubmitted(id: String): DataStoreResult<Boolean> = runCatchingSql {
        val sql = "SELECT submit_time IS NOT NULL FROM app_drafts WHERE id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) raise(DataStoreError.EntityNotFound)

                rs.requireBoolean(1).bind()
            }
        }
    }

    override fun listingExistsByLanguageForAppDraft(
        appDraftId: String,
        language: ListingLanguage,
    ): DataStoreResult<Boolean> = runCatchingSql {
        val sql = """
            SELECT EXISTS(
                SELECT 1 FROM app_draft_listings WHERE app_draft_id = ? AND language = ?
            )
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, appDraftId)
            stmt.setString(2, language.languageTag())
            stmt.executeQuery().use { rs -> rs.getSelectExistsResult().bind() }
        }
    }

    override fun listingExistsByIdForAppDraft(
        listingId: String,
        appDraftId: String,
    ): DataStoreResult<Boolean> = runCatchingSql {
        val sql = "SELECT EXISTS(SELECT 1 FROM app_draft_listings WHERE id = ? AND app_draft_id = ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, listingId)
            stmt.setString(2, appDraftId)
            stmt.executeQuery().use { rs -> rs.getSelectExistsResult().bind() }
        }
    }

    override fun listingIsDefault(listingId: String): DataStoreResult<Boolean> = runCatchingSql {
        val sql = "SELECT EXISTS(SELECT 1 FROM app_drafts WHERE default_app_draft_listing_id = ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, listingId)
            stmt.executeQuery().use { rs -> rs.getSelectExistsResult().bind() }
        }
    }

    override fun pendingListingIconUploadExistsByListingId(
        appDraftListingId: String,
    ): DataStoreResult<Boolean> = runCatchingSql {
        val sql = """
            SELECT EXISTS(
                SELECT 1 FROM pending_app_draft_listing_icon_uploads WHERE app_draft_listing_id = ?
            )
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, appDraftListingId)
            stmt.executeQuery().use { rs -> rs.getSelectExistsResult().bind() }
        }
    }

    override fun pendingUploadExistsByAppDraftId(
        appDraftId: String,
    ): DataStoreResult<Boolean> = runCatchingSql {
        val sql = "SELECT EXISTS(SELECT 1 FROM pending_app_draft_uploads WHERE app_draft_id = ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, appDraftId)
            stmt.executeQuery().use { rs -> rs.getSelectExistsResult().bind() }
        }
    }

    override fun saveListingIconUpload(
        upload: PendingAppDraftListingIconUpload.Incomplete,
        blob: ExternalBlob<ExternalBlob.Status.Pending>,
    ): DataStoreResult<Unit> = runCatchingSql {
        insertUnownedPendingBlob(connection, blob).bind()

        val sql = """
            INSERT INTO pending_app_draft_listing_icon_uploads
                (id, app_draft_listing_id, external_blob_id, object_key, create_time)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, upload.id)
            stmt.setString(2, upload.appDraftListingId)
            stmt.setString(3, upload.externalBlobId)
            stmt.setString(4, upload.objectKey)
            stmt.setObject(5, upload.createTime)
            stmt.executeSingleUpdate().bind()
        }

        val adoptSql = """
            UPDATE external_blobs
            SET pending_app_draft_listing_icon_upload_id = ?
            WHERE id = ?
        """.trimIndent()
        connection.prepareStatement(adoptSql).use { stmt ->
            stmt.setString(1, upload.id)
            stmt.setString(2, blob.id)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun saveUpload(
        upload: PendingAppDraftUpload.Incomplete,
        blob: ExternalBlob<ExternalBlob.Status.Pending>,
    ): DataStoreResult<Unit> = runCatchingSql {
        insertUnownedPendingBlob(connection, blob).bind()

        val sql = """
            INSERT INTO pending_app_draft_uploads
                (id, app_draft_id, external_blob_id, object_key, create_time)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, upload.id)
            stmt.setString(2, upload.appDraftId)
            stmt.setString(3, upload.externalBlobId)
            stmt.setString(4, upload.objectKey)
            stmt.setObject(5, upload.createTime)
            stmt.executeSingleUpdate().bind()
        }

        val adoptSql = "UPDATE external_blobs SET pending_app_draft_upload_id = ? WHERE id = ?"
        connection.prepareStatement(adoptSql).use { stmt ->
            stmt.setString(1, upload.id)
            stmt.setString(2, blob.id)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun updateListing(
        listingId: String,
        name: Option<String>,
        shortDescription: Option<String>,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = """
            UPDATE app_draft_listings
            SET name = COALESCE(?, name), short_description = COALESCE(?, short_description)
            WHERE id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, name.getOrNull(), Types.VARCHAR)
            stmt.setObject(2, shortDescription.getOrNull(), Types.VARCHAR)
            stmt.setString(3, listingId)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun updateDefaultListing(
        appDraftId: String,
        defaultAppDraftListingId: Option<String>,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = "UPDATE app_drafts SET default_app_draft_listing_id = ? WHERE id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, defaultAppDraftListingId.getOrNull())
            stmt.setString(2, appDraftId)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun updateSubmitTime(
        appDraftId: String,
        submitTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = "UPDATE app_drafts SET submit_time = ? WHERE id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, submitTime)
            stmt.setString(2, appDraftId)
            stmt.executeSingleUpdate().bind()
        }
    }
}

private class InMemoryAppPackageRepository(
    private val connection: Connection,
) : AppPackageRepository() {
    override fun findAppIdByAppDraftId(
        appDraftId: String,
    ): DataStoreResult<Option<ApplicationId>> = runCatchingSql {
        val sql = "SELECT app_id FROM app_packages WHERE app_draft_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, appDraftId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                val appId = ApplicationId
                    .fromString(rs.requireString("app_id").bind())
                    .toEitherBind { DataStoreError.IllegalState }

                Some(appId)
            }
        }
    }

    override fun findByAppDraftId(
        appDraftId: String,
    ): DataStoreResult<Option<AppPackage>> = runCatchingSql {
        val sql = """
            SELECT
                id,
                app_draft_id,
                external_blob_id,
                upload_event_time,
                app_id,
                version_code,
                version_name,
                target_sdk,
                signer_certificate,
                build_apks_result
            FROM app_packages
            WHERE app_draft_id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, appDraftId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                Some(rs.readAppPackage().bind())
            }
        }
    }

    override fun saveFromPendingUpload(
        pendingUploadId: String,
        appPackage: AppPackage,
        permissions: Map<NameAttribute, Option<SdkVersion>>,
        blobVersion: ExternalBlob.BlobVersion,
        replacedBlobDeleteTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        val releaseSql = """
            UPDATE external_blobs
            SET status = 'deleted', delete_time = ?, app_package_id = NULL
            WHERE app_package_id = (SELECT app_package_id FROM app_drafts WHERE id = ?)
        """.trimIndent()
        connection.prepareStatement(releaseSql).use { stmt ->
            stmt.setObject(1, replacedBlobDeleteTime)
            stmt.setString(2, appPackage.appDraftId)
            stmt.executeMultiUpdate().bind()
        }
        val detachSql = "UPDATE app_drafts SET app_package_id = NULL WHERE id = ?"
        connection.prepareStatement(detachSql).use { stmt ->
            stmt.setString(1, appPackage.appDraftId)
            stmt.executeSingleUpdate().bind()
        }
        connection.prepareStatement("DELETE FROM app_packages WHERE app_draft_id = ?").use { stmt ->
            stmt.setString(1, appPackage.appDraftId)
            stmt.executeMultiUpdate().bind()
        }

        val insertSql = """
            INSERT INTO app_packages (
                id,
                app_draft_id,
                external_blob_id,
                upload_event_time,
                app_id,
                version_code,
                version_name,
                target_sdk,
                signer_certificate,
                build_apks_result
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(insertSql).use { stmt ->
            stmt.setString(1, appPackage.id)
            stmt.setString(2, appPackage.appDraftId)
            stmt.setString(3, appPackage.externalBlobId)
            stmt.setObject(4, appPackage.uploadEventTime)
            stmt.setString(5, appPackage.appId.value)
            stmt.setInt(6, appPackage.versionCode.value)
            stmt.setString(7, appPackage.versionName.value)
            stmt.setInt(8, appPackage.targetSdk.value)
            stmt.setBytes(9, appPackage.signerCertificate.copyToByteArray())
            stmt.setBytes(10, appPackage.buildApksResult.copyToByteArray())
            stmt.executeSingleUpdate().bind()
        }

        val insertPermissionSql = """
            INSERT INTO app_package_permissions (app_package_id, name, max_sdk_version)
            VALUES (?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(insertPermissionSql).use { stmt ->
            for ((name, maxSdkVersion) in permissions) {
                stmt.setString(1, appPackage.id)
                stmt.setString(2, name.value)
                stmt.setObject(3, maxSdkVersion.map(SdkVersion::value).getOrNull(), Types.INTEGER)
                stmt.executeSingleUpdate().bind()
            }
        }

        // Hand the blob from the pending upload to the new package
        val commitSql = """
            UPDATE external_blobs
            SET status = 'committed',
                generation = ?,
                meta_generation = ?,
                pending_app_draft_upload_id = NULL,
                app_package_id = ?
            WHERE id = ? AND status = 'pending' AND service = ?
        """.trimIndent()
        connection.prepareStatement(commitSql).use { stmt ->
            val service = when (blobVersion) {
                is ExternalBlob.GcsBlobVersion -> {
                    stmt.setLong(1, blobVersion.generation)
                    stmt.setLong(2, blobVersion.metaGeneration)
                    "gcs"
                }

                is ExternalBlob.LocalBlobVersion -> {
                    stmt.setLong(1, blobVersion.generation)
                    stmt.setNull(2, Types.BIGINT)
                    "local"
                }
            }
            stmt.setString(3, appPackage.id)
            stmt.setString(4, appPackage.externalBlobId)
            stmt.setString(5, service)
            stmt.executeSingleUpdate().mapLeft { DataStoreError.ConsistencyViolation }.bind()
        }

        val completeSql = """
            UPDATE pending_app_draft_uploads
            SET processing_result = 'success', external_blob_id = NULL
            WHERE id = ? AND app_draft_id = ? AND processing_result IS NULL
        """.trimIndent()
        connection.prepareStatement(completeSql).use { stmt ->
            stmt.setString(1, pendingUploadId)
            stmt.setString(2, appPackage.appDraftId)
            stmt.executeSingleUpdate().bind()
        }

        val attachSql = "UPDATE app_drafts SET app_package_id = ? WHERE id = ?"
        connection.prepareStatement(attachSql).use { stmt ->
            stmt.setString(1, appPackage.id)
            stmt.setString(2, appPackage.appDraftId)
            stmt.executeSingleUpdate().bind()
        }
    }

}

private class InMemoryAppRepository(private val connection: Connection) : AppRepository() {
    override fun countInAppDraftOrganization(appDraftId: String): DataStoreResult<ULong> =
        runCatchingSql {
            val sql = """
                SELECT COUNT(1)
                FROM app_drafts
                JOIN apps ON apps.organization_id = app_drafts.organization_id
                WHERE app_drafts.id = ?
            """.trimIndent()
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, appDraftId)
                stmt.executeQuery().use { rs -> rs.getSelectCountResult().bind() }
            }
        }

    override fun findById(id: String): DataStoreResult<Option<App>> = runCatchingSql {
        val sql = """
            SELECT id, organization_id, default_app_listing_id, publicly_listed
            FROM apps
            WHERE id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                App(
                    id = rs.requireString("id").bind(),
                    organizationId = rs.requireString("organization_id").bind(),
                    defaultAppListingId = rs.requireString("default_app_listing_id").bind(),
                    publiclyListed = rs.requireBoolean("publicly_listed").bind()
                )
                    .some()
            }
        }
    }

    override fun saveWithDefaultListing(
        app: App,
        defaultListing: AppListing,
    ): DataStoreResult<Unit> = runCatchingSql {
        if (defaultListing.appId != app.id) {
            raise(DataStoreError.ConsistencyViolation)
        }

        val appInsertSql = """
            INSERT INTO apps (id, organization_id, default_app_listing_id, publicly_listed)
            VALUES (?, ?, ?, ?)
        """.trimIndent()
        val appUpdateSql = "UPDATE apps SET default_app_listing_id = ? WHERE id = ?"
        val listingInsertSql = "INSERT INTO app_listings (id, app_id, language) VALUES (?, ?, ?)"

        connection.prepareStatement(appInsertSql).use { stmt ->
            stmt.setString(1, app.id)
            stmt.setString(2, app.organizationId)
            stmt.setNull(3, Types.VARCHAR)
            stmt.setBoolean(4, app.publiclyListed)
            stmt.executeSingleUpdate().bind()
        }
        connection.prepareStatement(listingInsertSql).use { stmt ->
            stmt.setString(1, defaultListing.id)
            stmt.setString(2, defaultListing.appId)
            stmt.setString(3, defaultListing.language.languageTag())
            stmt.executeSingleUpdate().bind()
        }
        connection.prepareStatement(appUpdateSql).use { stmt ->
            stmt.setString(1, app.defaultAppListingId)
            stmt.setString(2, app.id)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun updatePubliclyListed(
        appId: String,
        publiclyListed: Boolean,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = "UPDATE apps SET publicly_listed = ? WHERE apps.id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setBoolean(1, publiclyListed)
            stmt.setString(2, appId)
            stmt.executeSingleUpdate().bind()
        }
    }
}

private class InMemoryAuthorizationRepository(
    private val connection: Connection,
) : AuthorizationRepository() {
    override fun hasPermission(request: HasPermissionRequest): DataStoreResult<Boolean> {
        // Every permission is granted by the same rule: the subject owns the organization the
        // resource belongs to. Only the path from the resource to organizations varies, so the
        // request kind selects a query and one binding site serves them all.
        val sql = when (request) {
            is HasPermissionRequest.CreateAppDraft -> """
                SELECT EXISTS(
                    SELECT 1 FROM organizations
                    WHERE organizations.id = ?
                    AND organizations.owner_user_id = ?
                )
            """.trimIndent()

            is HasPermissionRequest.UpdateApp,
            is HasPermissionRequest.ViewApp -> """
                SELECT EXISTS(
                    SELECT 1 FROM apps
                    JOIN organizations
                    ON organizations.id = apps.organization_id
                    WHERE apps.id = ?
                    AND organizations.owner_user_id = ?
                )
            """.trimIndent()

            is HasPermissionRequest.CreateAppDraftListing,
            is HasPermissionRequest.DeleteAppDraft,
            is HasPermissionRequest.DownloadAppDraft,
            is HasPermissionRequest.ReplaceAppDraftPackage,
            is HasPermissionRequest.SubmitAppDraft,
            is HasPermissionRequest.UpdateAppDraft,
            is HasPermissionRequest.ViewAppDraft -> """
                SELECT EXISTS(
                    SELECT 1 FROM app_drafts
                    JOIN organizations
                    ON organizations.id = app_drafts.organization_id
                    WHERE app_drafts.id = ?
                    AND organizations.owner_user_id = ?
                )
            """.trimIndent()

            is HasPermissionRequest.DeleteAppDraftListing,
            is HasPermissionRequest.DownloadAppDraftListingIcon,
            is HasPermissionRequest.UpdateAppDraftListing,
            is HasPermissionRequest.UploadAppDraftListingIcon,
            is HasPermissionRequest.ViewAppDraftListing -> """
                SELECT EXISTS(
                    SELECT 1 FROM app_draft_listings
                    JOIN app_drafts
                    ON app_drafts.id = app_draft_listings.app_draft_id
                    JOIN organizations
                    ON organizations.id = app_drafts.organization_id
                    WHERE app_draft_listings.id = ?
                    AND organizations.owner_user_id = ?
                )
            """.trimIndent()
        }

        return runCatchingSql {
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, request.resourceId)
                stmt.setString(2, request.subjectId)
                stmt.executeQuery().use { rs -> rs.getSelectExistsResult().bind() }
            }
        }
    }
}

private class InMemoryExternalBlobRepository(
    private val connection: Connection,
) : ExternalBlobRepository() {
    override fun findById(id: String): DataStoreResult<Option<ExternalBlob<*>>> = runCatchingSql {
        val sql = """
            SELECT
                id,
                create_time,
                service,
                status,
                bucket_name,
                object_key,
                generation,
                meta_generation,
                delete_time
            FROM external_blobs
            WHERE id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                val blobId = rs.requireString("id").bind()
                val createTime = rs.requireObject<OffsetDateTime>("create_time").bind()
                val service = rs.requireString("service").bind()
                val status = rs.requireString("status").bind()
                val bucketName = rs.requireString("bucket_name").bind()
                val objectKey = rs.requireString("object_key").bind()

                when (service) {
                    "local" -> ExternalBlob.Local(
                        id = blobId,
                        createTime = createTime,
                        bucketName = bucketName,
                        objectKey = objectKey,
                        status = when (status) {
                            "pending" -> ExternalBlob.Status.Pending
                            "committed" -> ExternalBlob.Status.Committed(
                                ExternalBlob.LocalBlobVersion(
                                    rs.requireLong("generation").bind(),
                                ),
                            )

                            "deleted" -> ExternalBlob.Status.Deleted(
                                rs.getSafeLong("generation").map {
                                    ExternalBlob.LocalBlobVersion(it)
                                },
                                rs.requireObject<OffsetDateTime>("delete_time").bind(),
                            )

                            else -> raise(DataStoreError.IllegalState)
                        },
                    )

                    "gcs" -> ExternalBlob.Gcs(
                        id = blobId,
                        createTime = createTime,
                        bucketName = bucketName,
                        objectKey = objectKey,
                        status = when (status) {
                            "pending" -> ExternalBlob.Status.Pending
                            "committed" -> ExternalBlob.Status.Committed(
                                ExternalBlob.GcsBlobVersion(
                                    rs.requireLong("generation").bind(),
                                    rs.requireLong("meta_generation").bind(),
                                ),
                            )

                            "deleted" -> ExternalBlob.Status.Deleted(
                                rs.getSafeLong("generation").map { generation ->
                                    ExternalBlob.GcsBlobVersion(
                                        generation,
                                        rs.requireLong("meta_generation").bind(),
                                    )
                                },
                                rs.requireObject<OffsetDateTime>("delete_time").bind(),
                            )

                            else -> raise(DataStoreError.IllegalState)
                        },
                    )

                    else -> raise(DataStoreError.IllegalState)
                }
                    .some()
            }
        }
    }

}

/**
 * Inserts a pending external blob which does not yet name the pending upload that owns it.
 *
 * H2 supports neither deferrable foreign key constraints nor INSERT queries in common table
 * expressions, so a blob and its owner cannot be written by a single statement. The caller must
 * insert the owning upload and then attach it to this blob before the transaction ends.
 *
 * @param connection the connection to insert the blob on.
 * @param blob the pending external blob to insert.
 */
private fun insertUnownedPendingBlob(
    connection: Connection,
    blob: ExternalBlob<ExternalBlob.Status.Pending>,
): DataStoreResult<Unit> {
    val sql = """
        INSERT INTO external_blobs (id, create_time, service, status, bucket_name, object_key)
        VALUES (?, ?, ?, 'pending', ?, ?)
    """.trimIndent()
    return connection.prepareStatement(sql).use { stmt ->
        stmt.setString(1, blob.id)
        stmt.setObject(2, blob.createTime)
        stmt.setString(
            3,
            when (blob) {
                is ExternalBlob.Gcs -> "gcs"
                is ExternalBlob.Local -> "local"
            }
        )
        stmt.setString(4, blob.bucketName)
        stmt.setString(5, blob.objectKey)
        stmt.executeSingleUpdate()
    }
}

/**
 * Marks the external blob owned by a given app draft listing's pending icon upload as deleted,
 * releasing it.
 *
 * @param connection the connection to release the blob on.
 * @param appDraftListingId the ID of the app draft listing whose icon blob should be released.
 * @param deleteTime the time at which the released blob is marked as deleted.
 */
private fun releaseListingIconBlob(
    connection: Connection,
    appDraftListingId: String,
    deleteTime: OffsetDateTime,
): DataStoreResult<Unit> {
    val sql = """
        UPDATE external_blobs
        SET status = 'deleted',
            delete_time = ?,
            pending_app_draft_listing_icon_upload_id = NULL
        WHERE pending_app_draft_listing_icon_upload_id IN (
            SELECT id FROM pending_app_draft_listing_icon_uploads WHERE app_draft_listing_id = ?
        )
    """.trimIndent()
    return connection.prepareStatement(sql).use { stmt ->
        stmt.setObject(1, deleteTime)
        stmt.setString(2, appDraftListingId)
        stmt.executeMultiUpdate()
    }
}

private class InMemoryOrganizationRepository(
    private val connection: Connection,
) : OrganizationRepository() {
    override fun findIdByOwnerUserId(userId: String): DataStoreResult<Option<String>> = runCatchingSql {
        val sql = "SELECT id FROM organizations WHERE owner_user_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                Some(rs.requireString("id").bind())
            }
        }
    }

    override fun saveWithOwner(
        organizationId: String,
        userId: String,
        externalUserId: ExternalUserId,
        createTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        val githubUserId = when (externalUserId) {
            is ExternalUserId.Github -> externalUserId.userId
        }

        val organizationInsertSql =
            "INSERT INTO organizations (id, owner_user_id, create_time) VALUES (?, ?, ?)"
        val userInsertSql =
            "INSERT INTO users (id, organization_id, create_time, github_user_id) VALUES (?, ?, ?, ?)"
        val organizationUpdateSql = "UPDATE organizations SET owner_user_id = ? WHERE id = ?"

        connection.prepareStatement(organizationInsertSql).use { stmt ->
            stmt.setString(1, organizationId)
            stmt.setNull(2, Types.VARCHAR)
            stmt.setObject(3, createTime)
            stmt.executeSingleUpdate().bind()
        }
        connection.prepareStatement(userInsertSql).use { stmt ->
            stmt.setString(1, userId)
            stmt.setString(2, organizationId)
            stmt.setObject(3, createTime)
            stmt.setLong(4, githubUserId)
            stmt.executeSingleUpdate().bind()
        }
        connection.prepareStatement(organizationUpdateSql).use { stmt ->
            stmt.setString(1, userId)
            stmt.setString(2, organizationId)
            stmt.executeSingleUpdate().bind()
        }
    }
}

private class InMemorySessionRepository(
    private val connection: Connection,
) : SessionRepository() {
    override fun create(
        idHash: Sha256Hash,
        userId: String,
        createTime: OffsetDateTime,
        expireTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = """
            INSERT INTO sessions (id_hash, user_id, create_time, expire_time) VALUES (?, ?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setBytes(1, idHash.digest().copyToByteArray())
            stmt.setString(2, userId)
            stmt.setObject(3, createTime)
            stmt.setObject(4, expireTime)
            stmt.executeSingleUpdate().bind()
        }
    }
}

private class InMemoryUserRepository(
    private val connection: Connection,
) : UserRepository() {
    override fun findIdByExternalUserId(
        externalUserId: ExternalUserId,
    ): DataStoreResult<Option<String>> = runCatchingSql {
        val githubUserId = when (externalUserId) {
            is ExternalUserId.Github -> externalUserId.userId
        }

        val sql = "SELECT id FROM users WHERE github_user_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, githubUserId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                Some(rs.requireString("id").bind())
            }
        }
    }

    override fun findIdBySessionIdHash(
        sessionIdHash: Sha256Hash,
        currentTime: OffsetDateTime,
    ): DataStoreResult<Option<String>> = runCatchingSql {
        val sql = "SELECT user_id FROM sessions WHERE id_hash = ? AND expire_time > ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setBytes(1, sessionIdHash.digest().copyToByteArray())
            stmt.setObject(2, currentTime)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return@use None

                Some(rs.requireString("user_id").bind())
            }
        }
    }
}

/**
 * Reads an [AppDraftApiView] from the current row.
 */
private fun ResultSet.readAppDraftApiView(): DataStoreResult<AppDraftApiView> = either {
    val appDraftId = requireString("id").bind()
    val createTime = requireObject<OffsetDateTime>("create_time").bind()
    val defaultAppDraftListingId = getSafeString("default_app_draft_listing_id")
    val appPackage = getSafeString("app_id").map { appId ->
        AppPackageApiView(
            androidApplicationId = ApplicationId.fromString(appId)
                .toEitherBind { DataStoreError.IllegalState },
            versionCode = VersionCode.fromInt(requireInt("version_code").bind())
                .toEitherBind { DataStoreError.IllegalState },
            versionName = VersionName
                .fromString(requireString("version_name").bind())
                .toEitherBind { DataStoreError.IllegalState },
            targetSdk = SdkVersion.fromInt(requireInt("target_sdk").bind())
                .toEitherBind { DataStoreError.IllegalState },
        )
    }

    when (val submitTime = getSafeObject<OffsetDateTime>("submit_time")) {
        None -> AppDraftApiView.Unsubmitted(
            id = appDraftId,
            createTime = createTime,
            defaultAppDraftListingId = defaultAppDraftListingId,
            appPackage = appPackage,
        )

        is Some -> AppDraftApiView.Submitted(
            id = appDraftId,
            createTime = createTime,
            defaultAppDraftListingId = defaultAppDraftListingId
                .toEitherBind { DataStoreError.IllegalState },
            appPackage = appPackage.toEitherBind { DataStoreError.IllegalState },
            submitTime = submitTime.value,
        )
    }
}

/**
 * Reads an [AppDraftListingApiView] from the current row, which must expose every
 * `app_draft_listings` column selected by this repository.
 */
private fun ResultSet.readAppDraftListingApiView(): DataStoreResult<AppDraftListingApiView> = either {
    AppDraftListingApiView(
        id = requireString("id").bind(),
        appDraftId = requireString("app_draft_id").bind(),
        language = ListingLanguage.fromLanguageTag(requireString("language").bind())
            .toEitherBind { DataStoreError.IllegalState },
        name = requireString("name").bind(),
        shortDescription = requireString("short_description").bind(),
    )
}

/**
 * Reads an [AppPackage] from the current row, which must expose every `app_packages` column
 * selected by this repository.
 */
private fun ResultSet.readAppPackage(): DataStoreResult<AppPackage> = either {
    AppPackage(
        id = requireString("id").bind(),
        appDraftId = requireString("app_draft_id").bind(),
        externalBlobId = requireString("external_blob_id").bind(),
        uploadEventTime = requireObject<OffsetDateTime>("upload_event_time").bind(),
        appId = ApplicationId.fromString(requireString("app_id").bind())
            .toEitherBind { DataStoreError.IllegalState },
        versionCode = VersionCode.fromInt(requireInt("version_code").bind())
            .toEitherBind { DataStoreError.IllegalState },
        versionName = VersionName.fromString(requireString("version_name").bind())
            .toEitherBind { DataStoreError.IllegalState },
        targetSdk = SdkVersion.fromInt(requireInt("target_sdk").bind())
            .toEitherBind { DataStoreError.IllegalState },
        signerCertificate = Bytes(requireBytes("signer_certificate").bind()),
        buildApksResult = Bytes(requireBytes("build_apks_result").bind()),
    )
}

private fun AppDraftUploadProcessingError.toColumnValue(): String = when (this) {
    AppDraftUploadProcessingError.AppDraftSubmitted -> "app_draft_submitted"
    is AppDraftUploadProcessingError.ApkSetParseFailed -> error.toColumnValue()
}

private fun ApkSetParseError.toColumnValue(): String = when (this) {
    ApkSetParseError.InvalidFormat -> "apk_set_invalid_format"
    ApkSetParseError.Io -> "apk_set_io_error"
    ApkSetParseError.Policy.Missing64BitCode -> "apk_set_missing_64_bit_code"
    ApkSetParseError.Policy.LowTargetSdk -> "apk_set_low_target_sdk"
    is ApkSetParseError.Policy.Apk -> error.toColumnValue()
}

private fun ApkParseError.Policy.toColumnValue(): String = when (this) {
    ApkParseError.Policy.NoModernSignature -> "apk_set_no_modern_signature"
    ApkParseError.Policy.SignedWithDebugCert -> "apk_set_signed_with_debug_cert"
    ApkParseError.Policy.SignedWithMultipleCerts -> "apk_set_signed_with_multiple_certs"
    ApkParseError.Policy.Unverified -> "apk_set_unverified"
    is ApkParseError.Policy.Manifest -> error.toColumnValue()
}

private fun AndroidManifest.FromXmlError.Policy.toColumnValue(): String = when (this) {
    AndroidManifest.FromXmlError.Policy.DuplicatePermission -> "apk_set_duplicate_permission"
    AndroidManifest.FromXmlError.Policy.InvalidApplicationId -> "apk_set_invalid_application_id"
    AndroidManifest.FromXmlError.Policy.DebuggableTrue -> "apk_set_debuggable"
    AndroidManifest.FromXmlError.Policy.TestOnlyTrue -> "apk_set_test_only"
    AndroidManifest.FromXmlError.Policy.MultipleApplicationElements ->
        "apk_set_multiple_application_elements"

    AndroidManifest.FromXmlError.Policy.MultipleUsesSdkElements ->
        "apk_set_multiple_uses_sdk_elements"

    AndroidManifest.FromXmlError.Policy.NoVersionCode -> "apk_set_no_version_code"
    AndroidManifest.FromXmlError.Policy.PermissionMaxSdkOutOfRange ->
        "apk_set_permission_max_sdk_out_of_range"

    AndroidManifest.FromXmlError.Policy.PermissionNameTooLong ->
        "apk_set_permission_name_too_long"

    AndroidManifest.FromXmlError.Policy.VersionCodeOutOfRange ->
        "apk_set_version_code_out_of_range"

    AndroidManifest.FromXmlError.Policy.VersionCodeMajorNonZero ->
        "apk_set_version_code_major_non_zero"

    AndroidManifest.FromXmlError.Policy.VersionNameTooLong -> "apk_set_version_name_too_long"
}

private fun processingResultFromColumnValue(
    value: String,
): Option<Either<AppDraftUploadProcessingError, Unit>> = when (value) {
    "success" -> Some(Unit.right())
    "app_draft_submitted" -> Some(AppDraftUploadProcessingError.AppDraftSubmitted.left())
    else -> apkSetParseErrorFromColumnValue(value)
        .map { AppDraftUploadProcessingError.ApkSetParseFailed(it).left() }
}

private fun apkSetParseErrorFromColumnValue(value: String): Option<ApkSetParseError> =
    when (value) {
        "apk_set_invalid_format" -> Some(ApkSetParseError.InvalidFormat)
        "apk_set_io_error" -> Some(ApkSetParseError.Io)
        "apk_set_missing_64_bit_code" -> Some(ApkSetParseError.Policy.Missing64BitCode)
        "apk_set_low_target_sdk" -> Some(ApkSetParseError.Policy.LowTargetSdk)
        else -> apkParseErrorFromColumnValue(value).map { ApkSetParseError.Policy.Apk(it) }
    }

private fun apkParseErrorFromColumnValue(value: String): Option<ApkParseError.Policy> =
    when (value) {
        "apk_set_no_modern_signature" -> Some(ApkParseError.Policy.NoModernSignature)
        "apk_set_signed_with_debug_cert" -> Some(ApkParseError.Policy.SignedWithDebugCert)
        "apk_set_signed_with_multiple_certs" -> Some(ApkParseError.Policy.SignedWithMultipleCerts)
        "apk_set_unverified" -> Some(ApkParseError.Policy.Unverified)
        else -> manifestParseErrorFromColumnValue(value).map { ApkParseError.Policy.Manifest(it) }
    }

private fun manifestParseErrorFromColumnValue(
    value: String,
): Option<AndroidManifest.FromXmlError.Policy> = when (value) {
    "apk_set_duplicate_permission" ->
        Some(AndroidManifest.FromXmlError.Policy.DuplicatePermission)

    "apk_set_invalid_application_id" ->
        Some(AndroidManifest.FromXmlError.Policy.InvalidApplicationId)

    "apk_set_debuggable" -> Some(AndroidManifest.FromXmlError.Policy.DebuggableTrue)
    "apk_set_test_only" -> Some(AndroidManifest.FromXmlError.Policy.TestOnlyTrue)
    "apk_set_multiple_application_elements" ->
        Some(AndroidManifest.FromXmlError.Policy.MultipleApplicationElements)

    "apk_set_multiple_uses_sdk_elements" ->
        Some(AndroidManifest.FromXmlError.Policy.MultipleUsesSdkElements)

    "apk_set_no_version_code" -> Some(AndroidManifest.FromXmlError.Policy.NoVersionCode)
    "apk_set_permission_max_sdk_out_of_range" ->
        Some(AndroidManifest.FromXmlError.Policy.PermissionMaxSdkOutOfRange)

    "apk_set_permission_name_too_long" ->
        Some(AndroidManifest.FromXmlError.Policy.PermissionNameTooLong)

    "apk_set_version_code_out_of_range" ->
        Some(AndroidManifest.FromXmlError.Policy.VersionCodeOutOfRange)

    "apk_set_version_code_major_non_zero" ->
        Some(AndroidManifest.FromXmlError.Policy.VersionCodeMajorNonZero)

    "apk_set_version_name_too_long" -> Some(AndroidManifest.FromXmlError.Policy.VersionNameTooLong)
    else -> None
}

private fun iconProcessingResultFromColumnValue(
    value: String,
): Option<AppDraftListingIconUploadProcessingResult> = when (value) {
    "success" -> Some(AppDraftListingIconUploadProcessingResult.Success)
    "app_draft_submitted" -> Some(AppDraftListingIconUploadProcessingResult.Error.AppDraftSubmitted)
    "invalid_image" -> Some(AppDraftListingIconUploadProcessingResult.Error.InvalidImage)
    "incorrect_image_dimensions" ->
        Some(AppDraftListingIconUploadProcessingResult.Error.IncorrectImageDimensions)

    else -> None
}
