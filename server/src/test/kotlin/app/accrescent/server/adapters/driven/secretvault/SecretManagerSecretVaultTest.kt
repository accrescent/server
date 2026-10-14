// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.secretvault

import app.accrescent.server.IntegrationTest
import app.accrescent.server.core.Bytes
import app.accrescent.server.core.unwrap
import app.accrescent.server.integrationTestProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val EXPECTED_SECRET_VALUE = "secretvalue123"

/**
 * Tests for [SecretManagerSecretVault].
 *
 * These tests authenticate to Google Cloud via application default credentials and require the
 * following integration test properties:
 *
 * - `secretManager.secretVersion`: the resource name of a secret version which the credentials can
 * access, e.g., `projects/<project>/secrets/<secret>/versions/1`. Its value must be
 * [EXPECTED_SECRET_VALUE].
 */
@IntegrationTest
class SecretManagerSecretVaultTest {
    private val secretVersionName = integrationTestProperty("secretManager.secretVersion")
    private val secretName = secretVersionName.substringBeforeLast("/versions/")

    @Test
    fun `getSecret returns expected value of existing secret version`() {
        val value = SecretManagerSecretVault().use { vault ->
            vault.getSecret(secretVersionName).unwrap()
        }

        assertEquals(Bytes(EXPECTED_SECRET_VALUE.encodeToByteArray()), value)
    }

    @Test
    fun `getSecret returns error for nonexistent secret version`() {
        val result = SecretManagerSecretVault().use { vault ->
            vault.getSecret("$secretName/versions/999999")
        }

        assertTrue(result.isLeft())
    }

    @Test
    fun `getSecret returns error for name which is not a secret version name`() {
        val result = SecretManagerSecretVault().use { vault -> vault.getSecret(secretName) }

        assertTrue(result.isLeft())
    }
}
