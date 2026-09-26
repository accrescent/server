// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * A TCP port in the range [1, 65535].
 *
 * @property value the underlying [UShort] representation of this value.
 */
@JvmInline
value class TcpPort private constructor(val value: UShort) {
    companion object {
        fun new(value: UShort): Option<TcpPort> {
            return if (value > 0u) {
                Some(TcpPort(value))
            } else {
                None
            }
        }
    }
}
