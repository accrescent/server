// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.blobstorage

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * The validity lifetime of a signed URI for a blob storage service.
 *
 * @property seconds the validity period in seconds of this URI lifetime since it was issued.
 */
@JvmInline
value class UriLifetime private constructor(val seconds: UInt) {
    companion object {
        val DEFAULT = UriLifetime(30u)

        /**
         * Construct a new URI lifetime.
         *
         * @param seconds the number of seconds the URI lifetime should have.
         * @return a URI lifetime for the number of seconds specified by [seconds], or [None] if
         * [seconds] was out of the range of valid values.
         */
        fun new(seconds: UInt): Option<UriLifetime> {
            return if (seconds in 1u..43200u) {
                Some(UriLifetime(seconds))
            } else {
                None
            }
        }
    }
}
