// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.ConsoleApi
import arrow.core.getOrElse
import build.buf.gen.accrescent.console.v1.AppDraftServiceGrpcKt
import build.buf.gen.accrescent.console.v1.CreateAppDraftRequest
import build.buf.gen.accrescent.console.v1.DeleteAppDraftRequest
import build.buf.gen.accrescent.console.v1.DeleteAppDraftResponse
import build.buf.gen.accrescent.console.v1.deleteAppDraftResponse
import kotlin.coroutines.CoroutineContext
import build.buf.gen.accrescent.console.v1.CreateAppDraftResponse as CreateAppDraftResponseProto

/**
 * gRPC implementation of the console API's `AppDraftService`.
 *
 * Calls expect to be intercepted by [CallContextInterceptor].
 *
 * @param context the coroutine context to run method implementations in.
 * @param consoleApi the console API to serve.
 */
class AppDraftServiceImpl(
    context: CoroutineContext,
    private val consoleApi: ConsoleApi,
) : AppDraftServiceGrpcKt.AppDraftServiceCoroutineImplBase(context) {
    override suspend fun createAppDraft(
        request: CreateAppDraftRequest,
    ): CreateAppDraftResponseProto {
        val context = CallContextInterceptor.CALL_CONTEXT_KEY.get()

        return consoleApi.createAppDraft(context, toDomain(request))
            .mapLeft(::toStatus)
            .getOrElse { throw it.asRuntimeException() }
            .let(::toProto)
    }

    override suspend fun deleteAppDraft(
        request: DeleteAppDraftRequest,
    ): DeleteAppDraftResponse {
        val context = CallContextInterceptor.CALL_CONTEXT_KEY.get()

        consoleApi.deleteAppDraft(context, toDomain(request))
            .mapLeft(::toStatus)
            .getOrElse { throw it.asRuntimeException() }

        return deleteAppDraftResponse {}
    }
}
