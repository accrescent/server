// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console2

import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.PositiveInt
import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.core.toEitherBind
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.IdType
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.ports.driven.datastore2.AppDraftSummarySortKey
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.datastore2.HasPermissionRequest
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.console2.ActiveAppDraftLimitExceededError
import app.accrescent.server.domain.ports.driving.api.console2.AppDraftListingTextAlreadyExistsError
import app.accrescent.server.domain.ports.driving.api.console2.AppDraftSummary
import app.accrescent.server.domain.ports.driving.api.console2.CallContext
import app.accrescent.server.domain.ports.driving.api.console2.ConsoleApi
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftListingTextError
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftListingTextRequest
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftListingTextResponse
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftResponse
import app.accrescent.server.domain.ports.driving.api.console2.DeleteAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.DeleteAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfError
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfResponse
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console2.InvalidPageTokenError
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesError
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesRequest
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesResponse
import app.accrescent.server.domain.ports.driving.api.console2.Organization
import app.accrescent.server.domain.ports.driving.api.console2.User
import app.accrescent.server.domain.ports.driving.api.console2.UserRole
import app.accrescent.server.impl.v1.ListAppDraftSummariesPageToken
import app.accrescent.server.impl.v1.listAppDraftSummariesPageToken
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.timestamp
import com.google.protobuf.util.Timestamps
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.encoding.Base64

/**
 * Primary implementation of [ConsoleApi].
 *
 * @param dataStore the data store to use for data persistence.
 * @param idGenerator the generator of resource IDs.
 * @param timestampSource the source of timestamps to use.
 * @param adminExternalUserId the external identity of the sole administrator user.
 */
class ConsoleApiImpl(
    private val dataStore: DataStore,
    private val idGenerator: IdGenerator,
    private val timestampSource: TimestampSource,
    private val adminExternalUserId: ExternalUserId,
) : ConsoleApi {
    override suspend fun createAppDraft(
        context: CallContext,
        request: CreateAppDraftRequest,
    ): Either<CreateAppDraftError, CreateAppDraftResponse> = either {
        val currentTime = timestampSource.now()
        val appDraftId = dataStore.runInTransaction { tx ->
            val userId = authenticateCaller(tx, context.sessionId, currentTime)

            val hasPermission = tx
                .hasPermission(HasPermissionRequest.CreateAppDraft(request.organizationId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission, ::InsufficientPermissionError)

            // App drafts can't be reviewed yet, so all of them are active
            val activeAppDraftCount = tx
                .countAppDraftsInOrg(request.organizationId)
                .bindMapLeft(::toServerError)
            ensure(
                activeAppDraftCount < ACTIVE_APP_DRAFT_LIMIT,
                ::ActiveAppDraftLimitExceededError,
            )

            val appDraftId = idGenerator.generateId(IdType.APP_DRAFT).bindMapLeft(::toServerError)
            tx
                .createAppDraft(appDraftId, request.organizationId, currentTime)
                .bindMapLeft(::toServerError)
            appDraftId
        }
            .bindMapLeft(::toServerError)
            .bind()

        CreateAppDraftResponse(appDraftId)
    }

    override suspend fun createAppDraftListingText(
        context: CallContext,
        request: CreateAppDraftListingTextRequest,
    ): Either<CreateAppDraftListingTextError, CreateAppDraftListingTextResponse> = either {
        val currentTime = timestampSource.now()
        val etag = dataStore.runInTransaction { tx ->
            val userId = authenticateCaller(tx, context.sessionId, currentTime)

            val hasPermission = tx
                .hasPermission(HasPermissionRequest.EditAppDraftListing(request.appDraftId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission, ::InsufficientPermissionError)

            // The app draft is guaranteed to exist since permission is granted
            tx
                .createAppDraftListingText(
                    appDraftId = request.appDraftId,
                    name = request.name,
                    shortDescription = request.shortDescription,
                )
                .bindMapLeft(::toServerError)
                .toEitherBind(::AppDraftListingTextAlreadyExistsError)
        }
            .bindMapLeft(::toServerError)
            .bind()

        CreateAppDraftListingTextResponse(etag)
    }

    override suspend fun deleteAppDraft(
        context: CallContext,
        request: DeleteAppDraftRequest,
    ): Either<DeleteAppDraftError, Unit> = either {
        val currentTime = timestampSource.now()
        dataStore.runInTransaction { tx ->
            val userId = authenticateCaller(tx, context.sessionId, currentTime)

            val hasPermission = tx
                .hasPermission(HasPermissionRequest.DeleteAppDraft(request.appDraftId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission, ::InsufficientPermissionError)

            // The app draft is guaranteed to exist since permission is granted
            tx.deleteAppDraft(request.appDraftId).bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()
    }

    override suspend fun getMyOrganization(
        context: CallContext,
    ): Either<GetMyOrganizationError, GetMyOrganizationResponse> = either {
        val currentTime = timestampSource.now()
        val organizationId = dataStore.runInTransaction { tx ->
            val userId = authenticateCaller(tx, context.sessionId, currentTime)

            // The organization is guaranteed to exist since the user is authenticated
            tx.requireOrgIdByOwnerUserId(userId).bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()

        GetMyOrganizationResponse(organizationId)
    }

    override suspend fun getOrganization(
        context: CallContext,
        request: GetOrganizationRequest,
    ): Either<GetOrganizationError, GetOrganizationResponse> = either {
        val currentTime = timestampSource.now()
        val organization = dataStore.runInTransaction { tx ->
            val userId = authenticateCaller(tx, context.sessionId, currentTime)

            val hasPermission = tx
                .hasPermission(HasPermissionRequest.ViewOrganization(request.organizationId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission, ::InsufficientPermissionError)

            // The organization is guaranteed to exist since permission is granted
            tx.requireOrgApiViewById(request.organizationId).bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()

        GetOrganizationResponse(
            Organization(
                id = organization.id,
                publishedAppLimit = PUBLISHED_APP_LIMIT,
                publishedAppCount = organization.publishedAppCount,
                activeAppDraftLimit = ACTIVE_APP_DRAFT_LIMIT,
                activeAppDraftCount = organization.activeAppDraftCount,
            ),
        )
    }

    override suspend fun getSelf(
        context: CallContext,
    ): Either<GetSelfError, GetSelfResponse> = either {
        val currentTime = timestampSource.now()
        val user = dataStore.runInTransaction { tx ->
            val userId = authenticateCaller(tx, context.sessionId, currentTime)

            val roles = tx.findUserIdByExternalUserId(adminExternalUserId)
                .bindMapLeft(::toServerError)
                // The user has the reviewer and publisher roles iff they're the administrator
                .filter { adminUserId -> userId == adminUserId }
                .map { setOf(UserRole.REVIEWER, UserRole.PUBLISHER) }
                .getOrElse(::emptySet)

            User(userId, roles)
        }
            .bindMapLeft(::toServerError)
            .bind()

        GetSelfResponse(user)
    }

    override suspend fun listAppDraftSummaries(
        context: CallContext,
        request: ListAppDraftSummariesRequest,
    ): Either<ListAppDraftSummariesError, ListAppDraftSummariesResponse> = either {
        val pageSize = request.pageSize.getOrElse { DEFAULT_PAGE_SIZE }.coerceAtMost(MAX_PAGE_SIZE)
        val after = request.pageToken.map { pageToken ->
            decodeListAppDraftSummariesPageToken(pageToken)
                .getOrElse { raise(InvalidPageTokenError()) }
        }
        val currentTime = timestampSource.now()

        val appDraftSummaries = dataStore.runInTransaction { tx ->
            val userId = authenticateCaller(tx, context.sessionId, currentTime)

            val hasPermission = tx
                .hasPermission(HasPermissionRequest.ViewAppDrafts(request.organizationId, userId))
                .bindMapLeft(::toServerError)
            ensure(hasPermission, ::InsufficientPermissionError)

            tx
                .listAppDraftSummaryApiViewsInOrg(request.organizationId, pageSize, after)
                .bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()

        // Return a page token if we haven't definitely reached the end of the collection
        val nextPageToken = if (appDraftSummaries.size == pageSize.value) {
            val lastAppDraftSummary = appDraftSummaries.last()
            val nextPageAfter = AppDraftSummarySortKey(
                createTime = lastAppDraftSummary.createTime,
                id = lastAppDraftSummary.id,
            )
            Some(encodeListAppDraftSummariesPageToken(nextPageAfter))
        } else {
            None
        }

        ListAppDraftSummariesResponse(
            appDraftSummaries = appDraftSummaries.map {
                // App drafts can't have listings or packages yet, so don't set those fields yet
                AppDraftSummary(it.id, None, None, None)
            },
            nextPageToken = nextPageToken,
        )
    }

    private companion object {
        private val ACTIVE_APP_DRAFT_LIMIT = NonNegativeInt.new(3).unwrap()
        private val DEFAULT_PAGE_SIZE = PositiveInt.new(50).unwrap()
        private val MAX_PAGE_SIZE = PositiveInt.new(50).unwrap()
        private val PUBLISHED_APP_LIMIT = NonNegativeInt.new(1).unwrap()
    }
}

private fun encodeListAppDraftSummariesPageToken(after: AppDraftSummarySortKey): String {
    val token = listAppDraftSummariesPageToken {
        lastAppDraftCreateTime = timestamp {
            seconds = after.createTime.toEpochSecond()
            nanos = after.createTime.nano
        }
        lastAppDraftId = after.id
    }

    return Base64.UrlSafe.encode(token.toByteArray())
}

private fun decodeListAppDraftSummariesPageToken(
    pageToken: String,
): Option<AppDraftSummarySortKey> {
    val token = try {
        ListAppDraftSummariesPageToken.parseFrom(Base64.UrlSafe.decode(pageToken))
    } catch (_: IllegalArgumentException) {
        return None
    } catch (_: InvalidProtocolBufferException) {
        return None
    }
    if (!token.hasLastAppDraftCreateTime() || !token.hasLastAppDraftId()) {
        return None
    }

    // Reject invalid timestamps so that clients can't pass ones which can't be represented in our
    // domain or DataStore backends
    val createTime = token.lastAppDraftCreateTime
    if (!Timestamps.isValid(createTime)) {
        return None
    }

    return Some(
        AppDraftSummarySortKey(
            createTime = Instant
                .ofEpochSecond(createTime.seconds, createTime.nanos.toLong())
                .atOffset(ZoneOffset.UTC),
            id = token.lastAppDraftId,
        ),
    )
}
