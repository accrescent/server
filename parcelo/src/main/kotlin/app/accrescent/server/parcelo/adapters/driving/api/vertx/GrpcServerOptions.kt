// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.adapters.driving.api.vertx

import io.vertx.grpc.common.WireFormat
import io.vertx.grpc.server.GrpcProtocol
import io.vertx.grpc.server.GrpcServerOptions

/**
 * Creates gRPC server options which enable only one protocol and wire format.
 *
 * @param protocol the gRPC protocol to enable.
 * @param format the gRPC wire format to enable.
 * @return the created gRPC server options.
 */
fun grpcServerOptions(protocol: GrpcProtocol, format: WireFormat): GrpcServerOptions {
    // Disable default formats and protocols so that if new ones are added in future Vert.x gRPC
    // versions, they aren't automatically enabled
    return GrpcServerOptions().apply {
        GrpcServerOptions.DEFAULT_ENABLED_PROTOCOLS.forEach { removeEnabledProtocol(it) }
        GrpcServerOptions.DEFAULT_ENABLED_FORMATS.forEach { removeEnabledFormat(it) }
    }
        .addEnabledProtocol(protocol)
        .addEnabledFormat(format)
}
