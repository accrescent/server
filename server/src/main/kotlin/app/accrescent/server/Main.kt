// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server

import app.accrescent.server.adapters.driven.cfgloader.SmallRyeConfigLoader
import app.accrescent.server.adapters.driven.datastore2.vertx.MemoryDataStore
import app.accrescent.server.adapters.driven.randomsource.SecureRandomSource
import app.accrescent.server.adapters.driven.timestampsource.SystemTimestampSource
import app.accrescent.server.adapters.driving.api.vertx.ApiVerticle
import app.accrescent.server.domain.IdGenerator
import app.accrescent.server.domain.api.authn.AuthenticationApiImpl
import arrow.core.getOrElse
import io.netty.util.NetUtil
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.net.InetSocketAddress
import java.util.function.Supplier
import kotlin.system.exitProcess

private const val VERSION = "0.16.0"

fun main() {
    val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

    // Load configuration
    val config = SmallRyeConfigLoader().loadConfig().getOrElse {
        logger.error("Failed to load configuration: {}", it)
        exitProcess(1)
    }

    // Set up the data store
    val vertx = Vertx.vertx()
    val dataStore = MemoryDataStore(vertx)
    dataStore.migrateToHead().getOrElse {
        logger.error("Failed migrating database to latest schema: {}", it)
        exitProcess(1)
    }

    // Set up the domain APIs
    val randomSource = SecureRandomSource()
    val authenticationApi = AuthenticationApiImpl(
        dataStore = dataStore,
        idGenerator = IdGenerator(randomSource),
        sessionLifetime = config.authn.session.lifetime,
        timestampSource = SystemTimestampSource(),
    )

    // Start the HTTP server
    val deployment = vertx.deployVerticle(
        Supplier {
            ApiVerticle(
                serverConfig = config.server,
                authnConfig = config.authn,
                authenticationApi = authenticationApi,
                randomSource = randomSource,
            )
        },
        // The default is to use only one of the event loops, so ensure we use all of them
        DeploymentOptions().setInstances(VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE),
    )
    deployment.otherwiseEmpty().await()
    if (deployment.failed()) {
        logger.error("Failed to start the server", deployment.cause())
        exitProcess(1)
    }

    // Register a shutdown hook for graceful shutdown
    try {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutting down the server")
                val close = vertx.close()
                close.otherwiseEmpty().await()
                if (close.failed()) {
                    logger.error("Failed to shut down the server cleanly", close.cause())
                } else {
                    logger.info("Server shut down")
                }
            },
        )
    } catch (_: IllegalStateException) {
        // The JVM is already shutting down, so there's nothing for us to do
        return
    }

    logger.info(
        "Accrescent server {} started on {}",
        VERSION,
        InetSocketAddress(config.server.address, config.server.port.value.toInt())
            .let(NetUtil::toSocketAddressString),
    )
}
