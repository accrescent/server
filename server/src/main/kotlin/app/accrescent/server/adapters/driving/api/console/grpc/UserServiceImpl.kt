// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.ConsoleApi
import arrow.core.getOrElse
import build.buf.gen.accrescent.console.v1.GetSelfRequest
import build.buf.gen.accrescent.console.v1.UserServiceGrpcKt
import io.grpc.protobuf.StatusProto
import kotlin.coroutines.CoroutineContext
import build.buf.gen.accrescent.console.v1.GetSelfResponse as GetSelfResponseProto

/**
 * gRPC implementation of the console API's `UserService`.
 *
 * Calls expect to be intercepted by [CallContextInterceptor].
 *
 * @param context the coroutine context to run method implementations in.
 * @param consoleApi the console API to serve.
 */
class UserServiceImpl(
    context: CoroutineContext,
    private val consoleApi: ConsoleApi,
) : UserServiceGrpcKt.UserServiceCoroutineImplBase(context) {
    override suspend fun getSelf(request: GetSelfRequest): GetSelfResponseProto {
        val context = CallContextInterceptor.CALL_CONTEXT_KEY.get()

        return consoleApi.getSelf(context)
            .mapLeft(::toStatus)
            .getOrElse { throw StatusProto.toStatusRuntimeException(it) }
            .let(::toProto)
    }
}
