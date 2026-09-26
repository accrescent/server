// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.crypto

import app.accrescent.server.core.Bytes
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import java.security.MessageDigest

/**
 * A SHA-256 hash over some data.
 */
@JvmInline
value class Sha256Hash private constructor(private val digest: Bytes) {
    companion object {
        /**
         * Creates a SHA-256 hash from an existing digest.
         *
         * @param digest the raw SHA-256 digest.
         * @return a SHA-256 hash if the digest is exactly 32 bytes long, otherwise [None].
         */
        fun fromDigest(digest: ByteArray): Option<Sha256Hash> {
            return if (digest.size == 32) {
                Some(Sha256Hash(Bytes(digest)))
            } else {
                None
            }
        }

        /**
         * Hashes data to produce a SHA-256 hash.
         *
         * @param data the data to produce a SHA-256 hash for.
         * @return the SHA-256 hash of [data].
         */
        fun hash(data: ByteArray): Sha256Hash {
            return MessageDigest.getInstance("SHA-256").digest(data).let(::Bytes).let(::Sha256Hash)
        }
    }

    /**
     * Returns the raw digest represented by this hash.
     *
     * @return the raw digest represented by this hash value.
     */
    fun digest(): Bytes {
        return digest
    }
}
