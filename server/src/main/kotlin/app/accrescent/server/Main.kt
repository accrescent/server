// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server

import app.accrescent.server.adapters.driving.api.vertx.ApiVerticle
import app.accrescent.server.core.TcpPort
import app.accrescent.server.core.unwrap
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import java.net.InetAddress
import java.util.function.Supplier

private val ADDRESS = InetAddress.ofLiteral("127.0.0.1")
private val PORT = TcpPort.new(8080u).unwrap()

fun main() {
    val vertx = Vertx.vertx()

    vertx
        .deployVerticle(
            Supplier { ApiVerticle(ADDRESS, PORT) },
            // The default is to use only one of the event loops, so ensure we use all of them
            DeploymentOptions().setInstances(VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE),
        )
        // Close the server if startup fails so that the JVM can exit
        .onFailure { vertx.close() }
        .await()
}
