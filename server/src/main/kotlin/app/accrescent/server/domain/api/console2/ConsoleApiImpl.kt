// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console2

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.datastore2.HasPermissionRequest
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.console2.CallContext
import app.accrescent.server.domain.ports.driving.api.console2.ConsoleApi
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console2.Organization
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure

private const val ACTIVE_APP_DRAFT_LIMIT = 3u
private const val PUBLISHED_APP_LIMIT = 1u

/**
 * Primary implementation of [ConsoleApi].
 *
 * @param dataStore the data store to use for data persistence.
 * @param timestampSource the source of timestamps to use.
 */
class ConsoleApiImpl(
    private val dataStore: DataStore,
    private val timestampSource: TimestampSource,
) : ConsoleApi {
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
}
