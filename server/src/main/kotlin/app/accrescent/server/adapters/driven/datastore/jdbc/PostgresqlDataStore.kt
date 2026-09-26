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
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.postgresql.util.PSQLState
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.OffsetDateTime
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

/**
 * Converts a [SQLException] thrown by this [DataStore] implementation into the appropriate typed
 * error.
 *
 * @return the [DataStoreError] corresponding to this exception's SQL state.
 */
private fun SQLException.toDataStoreError(): DataStoreError {
    return when (sqlState) {
        PSQLState.CHECK_VIOLATION.state,
        PSQLState.FOREIGN_KEY_VIOLATION.state,
        PSQLState.UNIQUE_VIOLATION.state -> DataStoreError.ConsistencyViolation

        PSQLState.DEADLOCK_DETECTED.state,
        PSQLState.SERIALIZATION_FAILURE.state -> DataStoreError.SerializationFailure

        else -> DataStoreError.Unknown
    }
}

/**
 * PostgreSQL-backed application data store.
 *
 * @param dataSource the data source to use for accessing the PostgreSQL database.
 * @param randomSource the random source to use for calculating backoff on transaction serialization
 * failures.
 */
class PostgresqlDataStore(
    private val dataSource: DataSource,
    randomSource: RandomSource,
) : DataStore(randomSource) {
    private val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("db/migration/postgresql")
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .load()

    override fun migrateToHead(): DataStoreResult<Unit> {
        return try {
            flyway.migrate()
            Either.Right(Unit)
        } catch (_: FlywayException) {
            Either.Left(DataStoreError.Unknown)
        }
    }

    override fun <T, E> runInTransaction(
        block: Raise<E>.(Transaction) -> T,
    ): DataStoreResult<Either<E, T>> {
        return try {
            dataSource.connection
        } catch (e: SQLException) {
            return e.toDataStoreError().left()
        }.use { connection ->
            try {
                connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
                connection.autoCommit = false
            } catch (e: SQLException) {
                return e.toDataStoreError().left()
            }

            val result: Either<E, T> = try {
                either { block(PostgresqlTransaction(connection)) }
            } catch (t: Throwable) {
                // Block threw, so attempt to roll back and rethrow the original throwable
                // unconditionally so the caller can handle it. A failed rollback is reported as a
                // suppressed exception rather than replacing the throwable the caller expects.
                try {
                    connection.rollback()
                } catch (e: SQLException) {
                    t.addSuppressed(e)
                }
                throw t
            }

            when (result) {
                // Block returned an error, so attempt to roll back, returning the block's error if
                // the rollback succeeds and the rollback error if it doesn't.
                is Either.Left -> try {
                    connection.rollback()
                    result.right()
                } catch (e: SQLException) {
                    e.toDataStoreError().left()
                }

                // Block succeeded, so attempt to commit, returning the block result if successful
                // and the commit error otherwise. No rollback is attempted after a failed commit,
                // since a commit which reports an error may still have been applied, in which case
                // the rollback wouldn't roll anything back.
                is Either.Right -> try {
                    connection.commit()
                    result.right()
                } catch (e: SQLException) {
                    e.toDataStoreError().left()
                }
            }
        }
    }
}

private class PostgresqlTransaction(connection: Connection) : DataStore.Transaction {
    override val appDrafts = PostgresqlAppDraftRepository(connection)
    override val appPackages = PostgresqlAppPackageRepository(connection)
    override val apps = PostgresqlAppRepository(connection)
    override val authz = PostgresqlAuthorizationRepository(connection)
    override val externalBlobs = PostgresqlExternalBlobRepository(connection)
    override val organizations = PostgresqlOrganizationRepository(connection)
    override val sessions = PostgresqlSessionRepository(connection)
    override val users = PostgresqlUserRepository(connection)
}

private class PostgresqlAppDraftRepository(
    private val connection: Connection,
) : DataStore.AppDraftRepository() {
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
        val sql = """
            WITH released_blob AS (
                UPDATE external_blobs
                SET status = 'deleted', delete_time = ?, pending_app_draft_upload_id = NULL
                WHERE pending_app_draft_upload_id = ?
            )
            UPDATE pending_app_draft_uploads
            SET processing_result = ?, external_blob_id = NULL
            WHERE id = ? AND processing_result IS NULL
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, blobDeleteTime)
            stmt.setString(2, pendingUploadId)
            stmt.setString(3, error.toColumnValue())
            stmt.setString(4, pendingUploadId)
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
        val sql = """
            WITH
                released_blobs AS (
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
                ),
                deleted_icon_uploads AS (
                    DELETE FROM pending_app_draft_listing_icon_uploads
                    WHERE app_draft_listing_id IN (
                        SELECT id FROM app_draft_listings WHERE app_draft_id = ?
                    )
                ),
                deleted_uploads AS (
                    DELETE FROM pending_app_draft_uploads WHERE app_draft_id = ?
                ),
                deleted_packages AS (
                    DELETE FROM app_packages WHERE app_draft_id = ?
                )
            DELETE FROM app_drafts WHERE id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, blobDeleteTime)
            for (parameterIndex in 2..8) {
                stmt.setString(parameterIndex, id)
            }
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun deleteListingById(
        id: String,
        blobDeleteTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = """
            WITH
                released_blob AS (
                    UPDATE external_blobs
                    SET status = 'deleted',
                        delete_time = ?,
                        pending_app_draft_listing_icon_upload_id = NULL
                    WHERE pending_app_draft_listing_icon_upload_id IN (
                        SELECT id
                        FROM pending_app_draft_listing_icon_uploads
                        WHERE app_draft_listing_id = ?
                    )
                ),
                deleted_icon_upload AS (
                    DELETE FROM pending_app_draft_listing_icon_uploads
                    WHERE app_draft_listing_id = ?
                )
            DELETE FROM app_draft_listings WHERE id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, blobDeleteTime)
            for (parameterIndex in 2..4) {
                stmt.setString(parameterIndex, id)
            }
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun deletePendingListingIconUploadByListingId(
        appDraftListingId: String,
        blobDeleteTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = """
            WITH released_blob AS (
                UPDATE external_blobs
                SET status = 'deleted',
                    delete_time = ?,
                    pending_app_draft_listing_icon_upload_id = NULL
                WHERE pending_app_draft_listing_icon_upload_id IN (
                    SELECT id
                    FROM pending_app_draft_listing_icon_uploads
                    WHERE app_draft_listing_id = ?
                )
            )
            DELETE FROM pending_app_draft_listing_icon_uploads WHERE app_draft_listing_id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, blobDeleteTime)
            stmt.setString(2, appDraftListingId)
            stmt.setString(3, appDraftListingId)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun deletePendingUploadByAppDraftId(
        appDraftId: String,
        blobDeleteTime: OffsetDateTime,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = """
            WITH released_blob AS (
                UPDATE external_blobs
                SET status = 'deleted', delete_time = ?, pending_app_draft_upload_id = NULL
                WHERE pending_app_draft_upload_id IN (
                    SELECT id FROM pending_app_draft_uploads WHERE app_draft_id = ?
                )
            )
            DELETE FROM pending_app_draft_uploads WHERE app_draft_id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, blobDeleteTime)
            stmt.setString(2, appDraftId)
            stmt.setString(3, appDraftId)
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
        val sql = """
            WITH new_blob AS (
                INSERT INTO external_blobs (
                    id,
                    create_time,
                    service,
                    status,
                    bucket_name,
                    object_key,
                    pending_app_draft_listing_icon_upload_id
                )
                VALUES (?, ?, ?, 'pending', ?, ?, ?)
            )
            INSERT INTO pending_app_draft_listing_icon_uploads (
                id,
                app_draft_listing_id,
                external_blob_id,
                object_key,
                create_time
            )
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setPendingBlob(blob, upload.id)
            stmt.setString(7, upload.id)
            stmt.setString(8, upload.appDraftListingId)
            stmt.setString(9, upload.externalBlobId)
            stmt.setString(10, upload.objectKey)
            stmt.setObject(11, upload.createTime)
            stmt.executeSingleUpdate().bind()
        }
    }

    override fun saveUpload(
        upload: PendingAppDraftUpload.Incomplete,
        blob: ExternalBlob<ExternalBlob.Status.Pending>,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = """
            WITH new_blob AS (
                INSERT INTO external_blobs (
                    id,
                    create_time,
                    service,
                    status,
                    bucket_name,
                    object_key,
                    pending_app_draft_upload_id
                )
                VALUES (?, ?, ?, 'pending', ?, ?, ?)
            )
            INSERT INTO pending_app_draft_uploads (
                id,
                app_draft_id,
                external_blob_id,
                object_key,
                create_time
            )
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setPendingBlob(blob, upload.id)
            stmt.setString(7, upload.id)
            stmt.setString(8, upload.appDraftId)
            stmt.setString(9, upload.externalBlobId)
            stmt.setString(10, upload.objectKey)
            stmt.setObject(11, upload.createTime)
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

private class PostgresqlAppPackageRepository(
    private val connection: Connection,
) : DataStore.AppPackageRepository() {
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
        val packageSql = """
            WITH
                completed_upload AS (
                    UPDATE pending_app_draft_uploads
                    SET processing_result = 'success', external_blob_id = NULL
                    WHERE id = ? AND processing_result IS NULL
                ),
                committed_blob AS (
                    UPDATE external_blobs
                    SET status = 'committed',
                        generation = ?,
                        meta_generation = ?,
                        pending_app_draft_upload_id = NULL,
                        app_package_id = ?
                    WHERE id = ? AND status = 'pending' AND service = ?
                ),
                released_blob AS (
                    UPDATE external_blobs
                    SET status = 'deleted', delete_time = ?, app_package_id = NULL
                    WHERE app_package_id = (SELECT app_package_id FROM app_drafts WHERE id = ?)
                ),
                deleted_package AS (
                    DELETE FROM app_packages
                    WHERE id = (SELECT app_package_id FROM app_drafts WHERE id = ?)
                ),
                new_package AS (
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
                )
            UPDATE app_drafts
            SET app_package_id = ?
            WHERE id = ?
            AND EXISTS (
                SELECT 1
                FROM pending_app_draft_uploads
                WHERE id = ?
                AND app_draft_id = app_drafts.id
                AND processing_result IS NULL
            )
        """.trimIndent()
        connection.prepareStatement(packageSql).use { stmt ->
            stmt.setString(1, pendingUploadId)
            val service = when (blobVersion) {
                is ExternalBlob.GcsBlobVersion -> {
                    stmt.setLong(2, blobVersion.generation)
                    stmt.setLong(3, blobVersion.metaGeneration)
                    "gcs"
                }

                is ExternalBlob.LocalBlobVersion -> {
                    stmt.setLong(2, blobVersion.generation)
                    stmt.setNull(3, Types.BIGINT)
                    "local"
                }
            }
            stmt.setString(4, appPackage.id)
            stmt.setString(5, appPackage.externalBlobId)
            stmt.setString(6, service)
            stmt.setObject(7, replacedBlobDeleteTime)
            stmt.setString(8, appPackage.appDraftId)
            stmt.setString(9, appPackage.appDraftId)
            stmt.setString(10, appPackage.id)
            stmt.setString(11, appPackage.appDraftId)
            stmt.setString(12, appPackage.externalBlobId)
            stmt.setObject(13, appPackage.uploadEventTime)
            stmt.setString(14, appPackage.appId.value)
            stmt.setInt(15, appPackage.versionCode.value)
            stmt.setString(16, appPackage.versionName.value)
            stmt.setInt(17, appPackage.targetSdk.value)
            stmt.setBytes(18, appPackage.signerCertificate.copyToByteArray())
            stmt.setBytes(19, appPackage.buildApksResult.copyToByteArray())
            stmt.setString(20, appPackage.id)
            stmt.setString(21, appPackage.appDraftId)
            stmt.setString(22, pendingUploadId)
            stmt.executeSingleUpdate().bind()
        }

        val permissionSql = """
            INSERT INTO app_package_permissions (app_package_id, name, max_sdk_version)
            VALUES (?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(permissionSql).use { stmt ->
            for ((name, maxSdkVersion) in permissions) {
                stmt.setString(1, appPackage.id)
                stmt.setString(2, name.value)
                stmt.setObject(
                    3,
                    maxSdkVersion.map(SdkVersion::value).getOrNull(),
                    Types.INTEGER,
                )
                stmt.executeSingleUpdate().bind()
            }
        }
    }
}

private class PostgresqlAppRepository(
    private val connection: Connection,
) : DataStore.AppRepository() {
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

                Some(
                    App(
                        id = rs.requireString("id").bind(),
                        organizationId = rs.requireString("organization_id").bind(),
                        defaultAppListingId = rs.requireString("default_app_listing_id").bind(),
                        publiclyListed = rs.requireBoolean("publicly_listed").bind(),
                    )
                )
            }
        }
    }

    override fun saveWithDefaultListing(
        app: App,
        defaultListing: AppListing,
    ): DataStoreResult<Unit> = runCatchingSql {
        val sql = """
            WITH new_app AS (
                INSERT INTO apps (id, organization_id, default_app_listing_id, publicly_listed)
                VALUES (?, ?, ?, ?)
                RETURNING id
            )
            INSERT INTO app_listings (id, app_id, language)
            SELECT ?, new_app.id, ? FROM new_app WHERE new_app.id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, app.id)
            stmt.setString(2, app.organizationId)
            stmt.setString(3, app.defaultAppListingId)
            stmt.setBoolean(4, app.publiclyListed)
            stmt.setString(5, defaultListing.id)
            stmt.setString(6, defaultListing.language.languageTag())
            stmt.setString(7, defaultListing.appId)
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

private class PostgresqlAuthorizationRepository(
    private val connection: Connection,
) : DataStore.AuthorizationRepository() {
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

private class PostgresqlExternalBlobRepository(
    private val connection: Connection,
) : DataStore.ExternalBlobRepository() {
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

                Some(
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
                )
            }
        }
    }
}

private class PostgresqlOrganizationRepository(
    private val connection: Connection,
) : DataStore.OrganizationRepository() {
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

        val sql = """
            WITH new_organization AS (
                INSERT INTO organizations (id, owner_user_id, create_time) VALUES (?, ?, ?)
            )
            INSERT INTO users (id, organization_id, create_time, github_user_id) VALUES (?, ?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, organizationId)
            stmt.setString(2, userId)
            stmt.setObject(3, createTime)
            stmt.setString(4, userId)
            stmt.setString(5, organizationId)
            stmt.setObject(6, createTime)
            stmt.setLong(7, githubUserId)
            stmt.executeSingleUpdate().bind()
        }
    }
}

private class PostgresqlSessionRepository(
    private val connection: Connection,
) : DataStore.SessionRepository() {
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

private class PostgresqlUserRepository(
    private val connection: Connection,
) : DataStore.UserRepository() {
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
 * Binds the first six parameters of an insert which creates a pending external blob alongside the
 * pending upload that owns it.
 *
 * @param blob the pending blob to bind.
 * @param ownerId the ID of the pending upload which owns the blob.
 */
private fun PreparedStatement.setPendingBlob(
    blob: ExternalBlob<ExternalBlob.Status.Pending>,
    ownerId: String,
) {
    setString(1, blob.id)
    setObject(2, blob.createTime)
    setString(
        3,
        when (blob) {
            is ExternalBlob.Gcs -> "gcs"
            is ExternalBlob.Local -> "local"
        }
    )
    setString(4, blob.bucketName)
    setString(5, blob.objectKey)
    setString(6, ownerId)
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
