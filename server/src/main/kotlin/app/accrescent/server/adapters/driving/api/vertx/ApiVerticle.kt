// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.vertx

import app.accrescent.server.adapters.driving.api.appstore.vertx.GrpcAppStoreApiServer
import app.accrescent.server.adapters.driving.api.console.vertx.GrpcWebConsoleApiServer
import app.accrescent.server.core.TcpPort
import io.vertx.core.http.HttpHeaders
import io.vertx.grpc.server.GrpcProtocol
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import java.net.InetAddress

/**
 * A verticle which serves the console API and app store API.
 *
 * The console API is served over gRPC-Web, while the app store API is served over gRPC.
 *
 * @param address the address to have the gRPC servers listen on.
 * @param port the port to have the gRPC servers listen on.
 */
class ApiVerticle(
    private val address: InetAddress,
    private val port: TcpPort,
) : CoroutineVerticle() {
    override suspend fun start() {
        val consoleServer = GrpcWebConsoleApiServer(vertx, coroutineContext)
        val appStoreServer = GrpcAppStoreApiServer(vertx, coroutineContext)

        vertx.createHttpServer()
            .requestHandler { request ->
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
}
