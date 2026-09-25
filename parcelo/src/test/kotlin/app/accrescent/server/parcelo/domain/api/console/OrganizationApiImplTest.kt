// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.api.console

import app.accrescent.server.parcelo.adapters.driven.datastore.jdbc.InMemoryDataStore
import app.accrescent.server.parcelo.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.parcelo.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.parcelo.core.unwrap
import app.accrescent.server.parcelo.core.unwrapErr
import app.accrescent.server.parcelo.domain.ports.driven.datastore.DataStore
import app.accrescent.server.parcelo.domain.ports.driven.timestampsource.TimestampSource
import app.accrescent.server.parcelo.domain.ports.driving.api.console.CallContext
import app.accrescent.server.parcelo.domain.ports.driving.api.console.GetMyOrganizationResponse
import app.accrescent.server.parcelo.domain.ports.driving.api.console.Organization
import app.accrescent.server.parcelo.domain.ports.driving.api.console.OrganizationApi
import app.accrescent.server.parcelo.domain.ports.driving.api.console.UnauthenticatedError
import arrow.core.None
import arrow.core.Some
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OrganizationApiImplTest {
    @Test
    fun `getMyOrganization returns Unauthenticated when not given a session ID`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val organizationApi = makeOrganizationApi(dataStore)

            val response = organizationApi.getMyOrganization(CallContext(None))

            assertEquals(UnauthenticatedError, response.unwrapErr())
        }
    }

    @Test
    fun `getMyOrganization returns Unauthenticated when session does not exist`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val organizationApi = makeOrganizationApi(dataStore)

            val response = organizationApi.getMyOrganization(CallContext(Some("session1")))

            assertEquals(UnauthenticatedError, response.unwrapErr())
        }
    }

    @Test
    fun `getMyOrganization returns Unauthenticated when session is expired`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val signInTime = FixedTimestampSource().now()
            val context = signIn(dataStore, timestampSource = FixedTimestampSource(signInTime))
            val organizationApi = makeOrganizationApi(
                dataStore = dataStore,
                timestampSource = FixedTimestampSource(signInTime.plusDays(2)),
            )

            val response = organizationApi.getMyOrganization(context)

            assertEquals(UnauthenticatedError, response.unwrapErr())
        }
    }

    @Test
    fun `getMyOrganization returns user's organization when authenticated`() {
        InMemoryDataStore(DeterministicRandomSource()).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val context = signIn(dataStore)
            val organizationApi = makeOrganizationApi(dataStore)

            val response = organizationApi.getMyOrganization(context)

            assertEquals(
                GetMyOrganizationResponse(Organization("org_1uMy9o9BqyoxomLjIEbctU")).right(),
                response,
            )
        }
    }

    private fun makeOrganizationApi(
        dataStore: DataStore,
        timestampSource: TimestampSource = FixedTimestampSource(),
    ): OrganizationApi {
        return OrganizationApiImpl(dataStore, timestampSource)
    }
}
