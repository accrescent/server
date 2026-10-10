// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.toOption

/**
 * An [Error] which wraps a [Throwable].
 *
 * This error traces a [Throwable]'s cause chain and converts it into an [Error] cause chain at
 * construction time to preserve causality information.
 *
 * @param t the [Throwable] to wrap into an [Error].
 */
class ThrowableError(t: Throwable) : Error() {
    override val message = t.message.toString()

    init {
        cause = t.cause.toOption().map(::ThrowableError)
    }
}
