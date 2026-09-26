// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.appstore.vertx

import app.accrescent.server.adapters.driving.api.appstore.grpc.AppServiceImpl
import app.accrescent.server.adapters.driving.api.vertx.grpcServerOptions
import io.vertx.core.Vertx
import io.vertx.grpc.common.WireFormat
import io.vertx.grpc.server.GrpcProtocol
import io.vertx.grpc.server.GrpcServer
import io.vertx.grpcio.server.GrpcIoServer
import kotlin.coroutines.CoroutineContext

/**
 * A gRPC server which serves the app store API
 *
 * @param vertx the Vert.x object to attach the server to.
 * @param context the coroutine context to run service method implementations in.
 */
class GrpcAppStoreApiServer(
    vertx: Vertx,
    context: CoroutineContext,
) : GrpcServer by createServer(vertx, context)

private fun createServer(vertx: Vertx, context: CoroutineContext): GrpcServer {
    val options = grpcServerOptions(GrpcProtocol.HTTP_2, WireFormat.PROTOBUF)
    val server = GrpcIoServer.server(vertx, options).addService(AppServiceImpl(context))

    return server
}
