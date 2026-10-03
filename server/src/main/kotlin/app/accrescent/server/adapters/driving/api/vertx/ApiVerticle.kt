// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.vertx

import app.accrescent.server.adapters.driving.api.appstore.vertx.GrpcAppStoreApiServer
import app.accrescent.server.adapters.driving.api.console.vertx.GrpcWebConsoleApiServer
import app.accrescent.server.core.TcpPort
import app.accrescent.server.domain.config.ShutdownTimeout
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.grpc.server.GrpcProtocol
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import java.net.InetAddress
import kotlin.time.toJavaDuration

/**
 * A verticle which serves the console API and app store API.
 *
 * The console API is served over gRPC-Web, while the app store API is served over gRPC.
 *
 * When stopped, the verticle stops accepting new connections and waits for in-flight requests to
 * complete for the duration of [shutdownTimeout]. If there are still active requests after
 * [shutdownTimeout], they are forcibly closed.
 *
 * @param address the address to have the gRPC servers listen on.
 * @param port the port to have the gRPC servers listen on.
 * @param shutdownTimeout the maximum amount of time to wait for in-flight requests to complete when
 * stopping.
 */
class ApiVerticle(
    private val address: InetAddress,
    private val port: TcpPort,
    private val shutdownTimeout: ShutdownTimeout,
) : CoroutineVerticle() {
    private lateinit var server: HttpServer

    override suspend fun start() {
        val consoleServer = GrpcWebConsoleApiServer(vertx, coroutineContext)
        val appStoreServer = GrpcAppStoreApiServer(vertx, coroutineContext)

        server = vertx.createHttpServer()
            .requestHandler { request ->
                // gRPC and gRPC-Web require POST, but the Vert.x gRPC server doesn't enforce it.
                // See https://github.com/eclipse-vertx/vertx-grpc/issues/376.
                if (request.method() != HttpMethod.POST) {
                    request.response()
                        .setStatusCode(HttpResponseStatus.METHOD_NOT_ALLOWED.code())
                        .putHeader(HttpHeaders.ALLOW, HttpMethod.POST.name())
                        .end()
                    return@requestHandler
                }

                // Route gRPC-Web requests to the console server and all other requests to the app
                // store server
                val contentType: String? = request.getHeader(HttpHeaders.CONTENT_TYPE)
                if (
                    contentType != null
                    && contentType.startsWith(GrpcProtocol.WEB.mediaType(), ignoreCase = true)
                ) {
                    consoleServer.handle(request)
                } else {
                    appStoreServer.handle(request)
                }
            }
            .listen(port.value.toInt(), address.hostAddress)
            .coAwait()
    }

    override suspend fun stop() {
        server.shutdown(shutdownTimeout.value.toJavaDuration()).coAwait()
    }
}
