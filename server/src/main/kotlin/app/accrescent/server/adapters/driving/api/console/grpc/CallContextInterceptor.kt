// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.adapters.driving.api.authn.vertx.SESSION_COOKIE_NAME
import app.accrescent.server.domain.ports.driving.api.console2.CallContext
import arrow.core.singleOrNone
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.netty.handler.codec.http.cookie.Cookie
import io.netty.handler.codec.http.cookie.ServerCookieDecoder

/**
 * A gRPC server interceptor which injects a [CallContext] into each request.
 *
 * The call's session ID is read from its session cookie if found.
 */
class CallContextInterceptor : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val sessionId = headers.getAll(COOKIE_KEY)
            ?.flatMap(ServerCookieDecoder.STRICT::decodeAll)
            .orEmpty()
            .filter { it.name() == SESSION_COOKIE_NAME }
            .singleOrNone()
            .map(Cookie::value)
        val context = Context.current().withValue(CALL_CONTEXT_KEY, CallContext(sessionId))

        return Contexts.interceptCall(context, call, headers, next)
    }

    companion object {
        private val COOKIE_KEY = Metadata.Key.of("cookie", Metadata.ASCII_STRING_MARSHALLER)

        val CALL_CONTEXT_KEY: Context.Key<CallContext> = Context.key("call-context")
    }
}
