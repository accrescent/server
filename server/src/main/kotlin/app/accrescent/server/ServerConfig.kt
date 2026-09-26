// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server

import app.accrescent.server.core.TcpPort
import io.smallrye.config.Converters
import io.smallrye.config.SmallRyeConfigBuilder
import java.net.InetAddress

/**
 * A server configuration.
 *
 * @property address the address for the server to listen on.
 * @property port the port for the server to listen on.
 */
data class ServerConfig(val address: InetAddress, val port: TcpPort) {
    companion object {
        /**
         * Loads the server configuration from its sources.
         *
         * @throws NoSuchElementException if a required property is missing or empty.
         * @throws IllegalArgumentException if a property's value is invalid.
         */
        fun load(): ServerConfig {
            val config = SmallRyeConfigBuilder().addDefaultSources().build()

            return ServerConfig(
                address = config.getValue("server.address", INET_ADDRESS_LITERAL_CONVERTER),
                port = config.getValue("server.port", TCP_PORT_CONVERTER),
            )
        }

        private val INET_ADDRESS_LITERAL_CONVERTER =
            Converters.newEmptyValueConverter { InetAddress.ofLiteral(it) }
        private val TCP_PORT_CONVERTER = Converters.newEmptyValueConverter { value ->
            value.toUShortOrNull()
                ?.let(TcpPort::new)
                ?.getOrNull()
                ?: throw IllegalArgumentException(
                    "\"$value\" is not an integer in the range [1, 65535]"
                )
        }
    }
}
