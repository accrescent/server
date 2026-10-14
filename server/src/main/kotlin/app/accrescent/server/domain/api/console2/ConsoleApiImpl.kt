// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console2

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.domain.ports.driven.datastore2.DataStore
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.console2.CallContext
import app.accrescent.server.domain.ports.driving.api.console2.ConsoleApi
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import arrow.core.Either
import arrow.core.raise.either

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
}
