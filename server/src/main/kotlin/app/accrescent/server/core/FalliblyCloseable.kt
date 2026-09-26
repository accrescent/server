// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(ExperimentalContracts::class)

package app.accrescent.server.core

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.context.Raise
import arrow.core.raise.either
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * A resource that may fail when closed, reporting the failure as an [Either] rather than throwing.
 *
 * This is the [Either]-returning analogue of [AutoCloseable]. Pair it with [use] to run an
 * operation against the resource and guarantee the resource is closed afterward, regardless of
 * whether the operation succeeds.
 */
fun interface FalliblyCloseable<E> {
    fun close(): Either<E, Unit>
}

sealed class UseError<out B, out C> {
    data class Block<E>(val error: E) : UseError<E, Nothing>()
    data class Close<E>(val error: E) : UseError<Nothing, E>()
    data class Both<B, C>(val blockError: B, val closeError: C) : UseError<B, C>()
}

/**
 * Runs [block] against this resource and then closes it, guaranteeing the close happens regardless
 * of whether [block] succeeds or throws. The [Either]-returning analogue of [kotlin.io.use].
 *
 * The two failure channels are kept distinct and combined without precedence:
 * - [block] fails, close succeeds: [UseError.Block].
 * - [block] succeeds, close fails: [UseError.Close].
 * - both fail: [UseError.Both], carrying each error as an equal peer.
 *
 * If [block] throws an exception, [close] is still called and the exception propagates. If [close]
 * throws after [block] throws, the [close] exception is added as a suppressed exception to the
 * [block] exception.
 */
inline fun <BlockSuccess, BlockError, CloseError, T : FalliblyCloseable<CloseError>> T.use(
    // block is crossinline so that non-local returns can't accidentally skip close() and leak the
    // resource
    crossinline block: Raise<BlockError>.(T) -> BlockSuccess,
): Either<UseError<BlockError, CloseError>, BlockSuccess> {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }

    val blockResult: Either<BlockError, BlockSuccess> = try {
        either { block(this@use) }
    } catch (e: Throwable) {
        runCatching { close() }.exceptionOrNull()?.let { e.addSuppressed(it) }
        throw e
    }
    val closeResult = close()

    val result = when (blockResult) {
        is Either.Left -> when (closeResult) {
            is Either.Left -> UseError.Both(blockResult.value, closeResult.value).left()
            is Either.Right -> UseError.Block(blockResult.value).left()
        }

        is Either.Right -> when (closeResult) {
            is Either.Left -> UseError.Close(closeResult.value).left()
            is Either.Right -> Either.Right(blockResult.value)
        }
    }

    return result
}
