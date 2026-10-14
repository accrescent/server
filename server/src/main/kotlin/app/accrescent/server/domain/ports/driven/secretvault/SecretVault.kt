// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.secretvault

import app.accrescent.server.core.Bytes
import app.accrescent.server.core.Error
import arrow.core.Either

/**
 * A read-only store of secret values such as credentials.
 *
 * This interface can be used to facilitate accessing secrets safely stored in external systems
 * (e.g., [Secret Manager](https://docs.cloud.google.com/secret-manager/docs/overview)) instead of
 * through, e.g., environment variables.
 */
interface SecretVault {
    /**
     * Retrieves the value of a secret.
     *
     * @param name the name of the secret to retrieve.
     * @return the value of the secret, or [SecretVaultError] if an error occurred while attempting
     * to retrieve the secret.
     */
    fun getSecret(name: String): Either<SecretVaultError, Bytes>
}

/**
 * An error which can occur while interacting with a [SecretVault].
 */
class SecretVaultError : Error() {
    override val message = "secret vault error"
}
