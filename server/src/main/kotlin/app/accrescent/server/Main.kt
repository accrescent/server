// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server

import app.accrescent.server.adapters.driven.cfgloader.SmallRyeConfigLoader
import app.accrescent.server.adapters.driving.api.vertx.ApiVerticle
import app.accrescent.server.domain.ports.driven.cfgloader.ConfigLoadError
import arrow.core.getOrElse
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.function.Supplier
import kotlin.system.exitProcess

fun main() {
    val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

    val config = SmallRyeConfigLoader().loadConfig().getOrElse {
        when (it) {
            is ConfigLoadError.InvalidProperty -> logger.error(
                "Invalid value for configuration property \"{}\": {}",
                it.name,
                it.message,
            )

            is ConfigLoadError.MissingProperty ->
                logger.error("Missing required configuration property \"{}\"", it.name)
        }
        exitProcess(1)
    }

    val deployment = Vertx.vertx().deployVerticle(
        Supplier { ApiVerticle(config.address, config.port) },
        // The default is to use only one of the event loops, so ensure we use all of them
        DeploymentOptions().setInstances(VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE),
    )
    deployment.otherwiseEmpty().await()
    if (deployment.failed()) {
        logger.error("Failed to start the server", deployment.cause())
        exitProcess(1)
    }
}
