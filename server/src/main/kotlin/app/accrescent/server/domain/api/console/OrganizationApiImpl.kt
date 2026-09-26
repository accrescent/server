// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.console.CallContext
import app.accrescent.server.domain.ports.driving.api.console.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console.Organization
import app.accrescent.server.domain.ports.driving.api.console.OrganizationApi
import app.accrescent.server.domain.ports.driving.api.console.toServerError
import arrow.core.Either
import arrow.core.raise.either

class OrganizationApiImpl(
    private val dataStore: DataStore,
    private val timestampSource: TimestampSource,
) : OrganizationApi {
    override fun getMyOrganization(
        context: CallContext,
    ): Either<GetMyOrganizationError, GetMyOrganizationResponse> = either {
        val organizationId = dataStore.runTxWithRetry { tx ->
            val userId = authenticateCaller(tx, context.sessionId, timestampSource.now()).bind()

            // The organization is guaranteed to exist since the user is authenticated
            tx.organizations.requireIdByOwnerUserId(userId).bindMapLeft(::toServerError)
        }
            .bindMapLeft(::toServerError)
            .bind()

        GetMyOrganizationResponse(Organization(organizationId))
    }
}
