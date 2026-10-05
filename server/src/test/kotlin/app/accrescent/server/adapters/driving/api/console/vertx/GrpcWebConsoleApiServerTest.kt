// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.vertx

import build.buf.gen.accrescent.console.v1.UserServiceGrpc
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.coroutines.EmptyCoroutineContext

class GrpcWebConsoleApiServerTest {
    @ParameterizedTest
    @ValueSource(strings = ["Sec-Fetch-Site", "sec-fetch-site"])
    fun `call with Sec-Fetch-Site set to same-origin is permitted`(headerName: String) {
        val statusCode = call(headerName to "same-origin")

        assertEquals(HttpResponseStatus.OK.code(), statusCode)
    }

    @Test
    fun `call without Sec-Fetch-Site is forbidden`() {
        val statusCode = call()

        assertEquals(HttpResponseStatus.FORBIDDEN.code(), statusCode)
    }

    @ParameterizedTest
    @ValueSource(strings = ["cross-site", "none", "same-site"])
    fun `call with Sec-Fetch-Site set to anything other than same-origin is forbidden`(value: String) {
        val statusCode = call("Sec-Fetch-Site" to value)

        assertEquals(HttpResponseStatus.FORBIDDEN.code(), statusCode)
    }

    @Test
    fun `call with multiple Sec-Fetch-Site headers is forbidden`() {
        val statusCode = call("Sec-Fetch-Site" to "same-origin", "sec-fetch-site" to "same-origin")

        assertEquals(HttpResponseStatus.FORBIDDEN.code(), statusCode)
    }

    companion object {
        private val vertx = Vertx.vertx()
        private val client = vertx.createHttpClient()
        private lateinit var server: HttpServer

        /**
         * A length-prefixed gRPC message frame containing an empty message.
         */
        private val EMPTY_MESSAGE_FRAME = byteArrayOf(0, 0, 0, 0, 0)

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server = vertx.createHttpServer()
                .requestHandler(GrpcWebConsoleApiServer(vertx, EmptyCoroutineContext))
                .listen(0, "localhost")
                .await()
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            vertx.close().await()
        }

        /**
         * Makes a gRPC-Web call to `GetSelf` with an empty request message.
         *
         * @param headers the (name, value) pairs of headers to send, one header per pair.
         * @return the HTTP status code of the response.
         */
        private fun call(vararg headers: Pair<String, String>): Int {
            val method = UserServiceGrpc.getGetSelfMethod().fullMethodName

            return client
                .request(HttpMethod.POST, server.actualPort(), "localhost", "/$method")
                .compose { request ->
                    request.putHeader(HttpHeaders.CONTENT_TYPE, "application/grpc-web+proto")
                    headers.forEach { (name, value) -> request.headers().add(name, value) }
                    request.send(Buffer.buffer(EMPTY_MESSAGE_FRAME))
                }
                .compose { response -> response.body().map { response.statusCode() } }
                .await()
        }
    }
}
