// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.cfgloader

import app.accrescent.server.core.TcpPort
import app.accrescent.server.domain.config.ServerConfig
import app.accrescent.server.domain.ports.driven.cfgloader.ConfigLoadError
import app.accrescent.server.domain.ports.driven.cfgloader.ConfigLoader
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.smallrye.config.Converters
import io.smallrye.config.SmallRyeConfig
import io.smallrye.config.SmallRyeConfigBuilder
import org.eclipse.microprofile.config.spi.Converter
import java.net.InetAddress

private const val ADDRESS_PROPERTY = "server.address"
private const val PORT_PROPERTY = "server.port"

/**
 * A configuration loader which loads via
 * [SmallRye Config](https://smallrye.io/smallrye-config/Latest/).
 *
 * The loader sources config values from SmallRye Config's
 * [default config sources](https://smallrye.io/smallrye-config/Latest/config/getting-started/#config-sources).
 */
class SmallRyeConfigLoader : ConfigLoader {
    override fun loadConfig(): Either<ConfigLoadError, ServerConfig> = either {
        val config = SmallRyeConfigBuilder().addDefaultSources().build()

        ServerConfig(
            address = loadProperty(config, ADDRESS_PROPERTY, INET_ADDRESS_LITERAL_CONVERTER).bind(),
            port = loadProperty(config, PORT_PROPERTY, TCP_PORT_CONVERTER).bind(),
        )
    }

    private fun <T> loadProperty(
        config: SmallRyeConfig,
        name: String,
        converter: Converter<T>,
    ): Either<ConfigLoadError, T> {
        return try {
            config.getValue(name, converter).right()
        } catch (e: IllegalArgumentException) {
            ConfigLoadError.InvalidProperty(name, e.message.toString()).left()
        } catch (_: NoSuchElementException) {
            ConfigLoadError.MissingProperty(name).left()
        }
    }

    private companion object {
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
