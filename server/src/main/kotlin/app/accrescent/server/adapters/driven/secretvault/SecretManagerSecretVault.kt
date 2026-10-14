// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.secretvault

import app.accrescent.server.core.Bytes
import app.accrescent.server.core.Error
import app.accrescent.server.core.ThrowableError
import app.accrescent.server.core.causedBy
import app.accrescent.server.domain.ports.driven.secretvault.SecretVault
import app.accrescent.server.domain.ports.driven.secretvault.SecretVaultError
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.api.gax.rpc.ApiException
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient
import java.io.IOException
import java.util.zip.CRC32C

/**
 * A [SecretVault] backed by Google Cloud's
 * [Secret Manager](https://docs.cloud.google.com/secret-manager/docs/overview).
 *
 * This class supports only the global service, not the regional service. To learn more about the
 * differences between the two, consult
 * [the official documentation](https://docs.cloud.google.com/secret-manager/docs/secret-manager-secrets-comparison).
 *
 * Secret names are secret version resource names, i.e., names of the form
 * `projects/<project>/secrets/<secret>/versions/<version>`.
 *
 * Authentication to Google Cloud occurs via
 * [application default credentials](https://docs.cloud.google.com/docs/authentication/application-default-credentials)
 * on the first call to [getSecret].
 */
class SecretManagerSecretVault : AutoCloseable, SecretVault {
    private val client = lazy { SecretManagerServiceClient.create() }

    override fun getSecret(name: String): Either<SecretVaultError, Bytes> {
        val payload = try {
            client.value.accessSecretVersion(name).payload
        } catch (e: ApiException) {
            return SecretVaultError().causedBy(ThrowableError(e)).left()
        } catch (e: IOException) {
            return SecretVaultError().causedBy(ThrowableError(e)).left()
        }

        // Verify the payload checksum to detect corruption in transit as recommended by
        // https://cloud.google.com/secret-manager/docs/access-secret-version
        val data = payload.data.toByteArray()
        val checksum = CRC32C().apply { update(data) }.value
        if (checksum != payload.dataCrc32C) {
            return SecretVaultError()
                .causedBy(ChecksumMismatchError(name, payload.dataCrc32C, checksum))
                .left()
        }

        return Bytes(data).right()
    }

    override fun close() {
        if (client.isInitialized()) {
            client.value.close()
        }
    }
}

/**
 * A secret version's payload didn't match the checksum Secret Manager provided for it.
 *
 * @param name the name of the secret version.
 * @param expected the checksum Secret Manager provided.
 * @param actual the checksum of the received payload.
 */
private class ChecksumMismatchError(name: String, expected: Long, actual: Long) : Error() {
    override val message =
        "payload checksum mismatch for secret version \"$name\": expected $expected, got $actual"
}
