// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain

import app.accrescent.server.core.bindMapLeft
import app.accrescent.server.domain.encoding.Base62
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import arrow.core.Either
import arrow.core.raise.either

private const val ID_BYTE_LENGTH = 16

data object IdGenerationError

/**
 * Generator for resource identifiers.
 */
class IdGenerator(private val randomSource: RandomSource) {
    /**
     * Generates a new resource ID.
     *
     * Resource identifiers are opaque strings and are usually cryptographically unique.
     *
     * This method is thread-safe.
     *
     * @param type the type of resource ID to generate
     * @return the generated resource ID.
     */
    fun generateId(type: IdType): Either<IdGenerationError, String> = either {
        val randomBytes = ByteArray(ID_BYTE_LENGTH)
            .also { randomSource.fillRandomBytes(it).bindMapLeft { IdGenerationError } }
        val encodedBytes = Base62.encode(randomBytes)
        val prefix = when (type) {
            IdType.APP -> "app"
            IdType.APP_DRAFT -> "ad"
            IdType.APP_DRAFT_LISTING -> "adl"
            IdType.APP_PACKAGE -> "pkg"
            IdType.BLOB_OBJECT_KEY -> "obj"
            IdType.EXTERNAL_BLOB -> "blob"
            IdType.ORGANIZATION -> "org"
            IdType.PENDING_APP_DRAFT_UPLOAD -> "adu"
            IdType.PENDING_APP_DRAFT_LISTING_ICON_UPLOAD -> "adliu"
            IdType.SESSION -> "s"
            IdType.USER -> "u"
        }

        return Either.Right("${prefix}_$encodedBytes")
    }
}
