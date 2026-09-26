// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.console

import app.accrescent.server.adapters.driven.datastore.jdbc.InMemoryDataStore
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.authn.ExternalUserId
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days

class SessionApiImplTest {
    @Test
    fun `calling createSession twice for same external user returns two different sessions`() {
        val randomSource = DeterministicRandomSource()
        InMemoryDataStore(randomSource).use { dataStore ->
            dataStore.migrateToHead().unwrap()
            val idGenerator = IdGenerator(randomSource)
            val timestampSource = FixedTimestampSource()
            val sessionApi = SessionApiImpl(dataStore, idGenerator, 1.days, timestampSource)

            val externalUserId = ExternalUserId.Github(1)
            val session1Id = sessionApi.createSession(externalUserId).unwrap().sessionId
            val session2Id = sessionApi.createSession(externalUserId).unwrap().sessionId

            assertNotEquals(session1Id, session2Id)
        }
    }
}
