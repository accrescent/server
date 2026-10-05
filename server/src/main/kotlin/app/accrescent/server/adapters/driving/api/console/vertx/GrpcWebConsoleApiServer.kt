// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.vertx

import app.accrescent.server.adapters.driving.api.console.grpc.AppDraftServiceImpl
import app.accrescent.server.adapters.driving.api.console.grpc.AppEditServiceImpl
import app.accrescent.server.adapters.driving.api.console.grpc.AppServiceImpl
import app.accrescent.server.adapters.driving.api.console.grpc.OrganizationServiceImpl
import app.accrescent.server.adapters.driving.api.console.grpc.ReviewServiceImpl
import app.accrescent.server.adapters.driving.api.console.grpc.UserServiceImpl
import app.accrescent.server.adapters.driving.api.vertx.grpcServerOptions
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerRequest
import io.vertx.grpc.common.WireFormat
import io.vertx.grpc.server.GrpcProtocol
import io.vertx.grpc.server.GrpcServer
import io.vertx.grpcio.server.GrpcIoServer
import kotlin.coroutines.CoroutineContext

private const val SEC_FETCH_SITE_HEADER_NAME = "Sec-Fetch-Site"
private const val SAME_ORIGIN = "same-origin"

/**
 * A gRPC-Web server which serves the console API using the binary wire format.
 *
 * All requests are protected against cross-site request forgery (CSRF). Requests are permitted iff
 * they have a single `Sec-Fetch-Site` header set to `same-origin`. Otherwise, they're rejected with
 * HTTP 403 Forbidden.
 *
 * See
 * https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#how-to-treat-fetch-metadata-headers-on-the-server-side
 * for more information on this approach to CSRF protection.
 *
 * @param vertx the Vert.x object to attach the server to.
 * @param context the coroutine context to run service method implementations in.
 */
class GrpcWebConsoleApiServer(
    vertx: Vertx,
    context: CoroutineContext,
) : Handler<HttpServerRequest> {
    private val server = createServer(vertx, context)

    override fun handle(request: HttpServerRequest) {
        val secFetchSite = request.headers().getAll(SEC_FETCH_SITE_HEADER_NAME).singleOrNull()

        if (secFetchSite == SAME_ORIGIN) {
            server.handle(request)
        } else {
            request.response().setStatusCode(HttpResponseStatus.FORBIDDEN.code()).end()
        }
    }
}

private fun createServer(vertx: Vertx, context: CoroutineContext): GrpcServer {
    val options = grpcServerOptions(GrpcProtocol.WEB, WireFormat.PROTOBUF)
    val server = GrpcIoServer.server(vertx, options)
        .addService(AppDraftServiceImpl(context))
        .addService(AppEditServiceImpl(context))
        .addService(AppServiceImpl(context))
        .addService(OrganizationServiceImpl(context))
        .addService(ReviewServiceImpl(context))
        .addService(UserServiceImpl(context))

    return server
}
