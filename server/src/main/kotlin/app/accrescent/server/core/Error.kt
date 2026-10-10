// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.core

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * The core error type used throughout this codebase.
 *
 * All errors returned by methods in this codebase should usually extend this class. It provides
 * causality and display information which make it convenient to chain with other errors and print
 * to the console.
 *
 * @property message the error message for this error. Should be lowercase and include details of
 * the error, but not its cause.
 * @property cause the error which caused this error, if any.
 */
abstract class Error {
    abstract val message: String
    var cause: Option<Error> = None

    override fun toString(): String {
        return cause.fold({ message }, { "$message: $it" })
    }
}

/**
 * Sets this error's cause.
 *
 * @param cause the error which caused this error.
 * @return this error.
 */
fun <E : Error> E.causedBy(cause: Error): E {
    return apply { this.cause = Some(cause) }
}
