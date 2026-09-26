// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console

import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.ports.driven.datastore.DataStore
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import app.accrescent.server.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.domain.ports.driving.api.console.CallContext
import arrow.core.Some
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Signs a new user up, creates a login session, and returns the necessary context to make calls as
 * the new user.
 */
fun signIn(
    dataStore: DataStore,
    randomSource: RandomSource = DeterministicRandomSource(),
    externalUserId: ExternalUserId = ExternalUserId.Github(1),
    timestampSource: TimestampSource = FixedTimestampSource(),
    sessionLifetime: Duration = 1.days,
): CallContext {
    val sessionApi = SessionApiImpl(
        dataStore = dataStore,
        idGenerator = IdGenerator(randomSource),
        sessionLifetime = sessionLifetime,
        timestampSource = timestampSource,
    )
    val sessionId = sessionApi.createSession(externalUserId).unwrap().sessionId

    return CallContext(Some(sessionId))
}

/**
 * Returns the ID of the organization owned by the caller as identified by its context.
 */
fun getMyOrganizationId(
    dataStore: DataStore,
    context: CallContext,
    timestampSource: TimestampSource = FixedTimestampSource(),
): String {
    return OrganizationApiImpl(dataStore, timestampSource)
        .getMyOrganization(context)
        .unwrap()
        .organization
        .id
}
