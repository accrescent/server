// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server

import app.accrescent.server.adapters.driving.api.vertx.ApiVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import java.util.function.Supplier

fun main() {
    val config = ServerConfig.load()

    val vertx = Vertx.vertx()
    vertx
        .deployVerticle(
            Supplier { ApiVerticle(config.address, config.port) },
            // The default is to use only one of the event loops, so ensure we use all of them
            DeploymentOptions().setInstances(VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE),
        )
        // Close the server if startup fails so that the JVM can exit
        .onFailure { vertx.close() }
        .await()
}
