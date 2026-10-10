// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.api.authn

import app.accrescent.server.adapters.driven.datastore2.vertx.MemoryDataStore
import app.accrescent.server.adapters.driven.randomsource.DeterministicRandomSource
import app.accrescent.server.adapters.driven.timestampsource.FixedTimestampSource
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.config.SessionLifetime
import app.accrescent.server.domain.ports.driving.api.authn.AuthenticationApi
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class AuthenticationApiImplTest {
    @Test
    suspend fun `calling createSession twice for same external user returns two different sessions`() {
        withAuthenticationApi { api ->
            val caller = ExternalUserId.Github(1)

            val firstResponse = api.createSession(caller).unwrap()
            val secondResponse = api.createSession(caller).unwrap()

            assertNotEquals(firstResponse.sessionId, secondResponse.sessionId)
        }
    }

    private companion object {
        private val vertx = Vertx.vertx()

        @AfterAll
        @JvmStatic
        fun closeVertx() {
            vertx.close().await()
        }

        private suspend fun withAuthenticationApi(block: suspend (AuthenticationApi) -> Unit) {
            val dataStore = MemoryDataStore(vertx)
            try {
                dataStore.migrateToHead().unwrap()
                val api = AuthenticationApiImpl(
                    dataStore = dataStore,
                    idGenerator = IdGenerator(DeterministicRandomSource()),
                    sessionLifetime = SessionLifetime.parse("24h").unwrap(),
                    timestampSource = FixedTimestampSource(),
                )

                block(api)
            } finally {
                dataStore.close().unwrap()
            }
        }
    }
}
