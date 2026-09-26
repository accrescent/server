// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * Attempts to downcast this value to [U].
 *
 * @param U the type to attempt to downcast to.
 * @return a value of type [U] if downcasting succeeds, otherwise [None].
 */
inline fun <reified U : Any> Any.downcast(): Option<U> {
    return when (this) {
        is U -> Some(this)
        else -> None
    }
}
