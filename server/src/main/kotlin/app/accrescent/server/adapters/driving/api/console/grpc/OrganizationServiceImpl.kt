// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.ConsoleApi
import arrow.core.getOrElse
import build.buf.gen.accrescent.console.v1.GetMyOrganizationRequest
import build.buf.gen.accrescent.console.v1.OrganizationServiceGrpcKt
import kotlin.coroutines.CoroutineContext
import build.buf.gen.accrescent.console.v1.GetMyOrganizationResponse as GetMyOrganizationResponseProto

/**
 * gRPC implementation of the console API's `OrganizationService`.
 *
 * Calls expect to be intercepted by [CallContextInterceptor].
 *
 * @param context the coroutine context to run method implementations in.
 * @param consoleApi the console API to serve.
 */
class OrganizationServiceImpl(
    context: CoroutineContext,
    private val consoleApi: ConsoleApi,
) : OrganizationServiceGrpcKt.OrganizationServiceCoroutineImplBase(context) {
    override suspend fun getMyOrganization(
        request: GetMyOrganizationRequest,
    ): GetMyOrganizationResponseProto {
        val context = CallContextInterceptor.CALL_CONTEXT_KEY.get()

        return consoleApi.getMyOrganization(context)
            .mapLeft(::toStatus)
            .getOrElse { throw it.asRuntimeException() }
            .let(::toProto)
    }
}
