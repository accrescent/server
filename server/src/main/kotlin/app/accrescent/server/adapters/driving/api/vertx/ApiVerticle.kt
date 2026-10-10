// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.vertx

import app.accrescent.server.adapters.driving.api.appstore.vertx.GrpcAppStoreApiServer
import app.accrescent.server.adapters.driving.api.authn.vertx.AUTHN_PATH_PREFIX
import app.accrescent.server.adapters.driving.api.authn.vertx.AuthnServer
import app.accrescent.server.adapters.driving.api.console.vertx.GrpcWebConsoleApiServer
import app.accrescent.server.domain.config.AuthnConfig
import app.accrescent.server.domain.config.ServerConfig
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import app.accrescent.server.domain.ports.driving.api.authn.AuthenticationApi
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.grpc.server.GrpcProtocol
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import kotlin.time.toJavaDuration

/**
 * A verticle which serves the authentication endpoints, console API, and app store API.
 *
 * The authentication endpoints are served under [AUTHN_PATH_PREFIX]. The console API is served over
 * gRPC-Web, while the app store API is served over gRPC.
 *
 * @param serverConfig the server configuration for the HTTP server.
 * @param authnConfig the user authentication configuration.
 * @param authenticationApi the user authentication API to use.
 * @param randomSource the source of randomness to use.
 */
class ApiVerticle(
    private val serverConfig: ServerConfig,
    private val authnConfig: AuthnConfig,
    private val authenticationApi: AuthenticationApi,
    private val randomSource: RandomSource,
) : CoroutineVerticle() {
    private lateinit var server: HttpServer

    override suspend fun start() {
        val authnServer =
            AuthnServer(vertx, coroutineContext, authnConfig, authenticationApi, randomSource)
        val consoleServer = GrpcWebConsoleApiServer(vertx, coroutineContext)
        val appStoreServer = GrpcAppStoreApiServer(vertx, coroutineContext)

        server = vertx.createHttpServer()
            .requestHandler { request ->
                // Route authentication requests to the authentication server
                if (request.path()?.startsWith(AUTHN_PATH_PREFIX) == true) {
                    authnServer.handle(request)
                    return@requestHandler
                }

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
            .listen(serverConfig.port.value.toInt(), serverConfig.address.hostAddress)
            .coAwait()
    }

    override suspend fun stop() {
        server.shutdown(serverConfig.shutdownTimeout.value.toJavaDuration()).coAwait()
    }
}
