// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.adapters.driving.api.console.vertx

import app.accrescent.server.parcelo.adapters.driving.api.console.grpc.AppDraftServiceImpl
import app.accrescent.server.parcelo.adapters.driving.api.console.grpc.AppEditServiceImpl
import app.accrescent.server.parcelo.adapters.driving.api.console.grpc.AppServiceImpl
import app.accrescent.server.parcelo.adapters.driving.api.console.grpc.OrganizationServiceImpl
import app.accrescent.server.parcelo.adapters.driving.api.console.grpc.ReviewServiceImpl
import app.accrescent.server.parcelo.adapters.driving.api.console.grpc.UserServiceImpl
import app.accrescent.server.parcelo.adapters.driving.api.vertx.grpcServerOptions
import io.vertx.core.Vertx
import io.vertx.grpc.common.WireFormat
import io.vertx.grpc.server.GrpcProtocol
import io.vertx.grpc.server.GrpcServer
import io.vertx.grpcio.server.GrpcIoServer
import kotlin.coroutines.CoroutineContext

/**
 * A gRPC-Web server which serves the console API using the binary wire format.
 *
 * @param vertx the Vert.x object to attach the server to.
 * @param context the coroutine context to run service method implementations in.
 */
class GrpcWebConsoleApiServer(
    vertx: Vertx,
    context: CoroutineContext,
) : GrpcServer by createServer(vertx, context)

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