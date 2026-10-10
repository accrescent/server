// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.cfgloader

import app.accrescent.server.core.NonEmptyString
import app.accrescent.server.core.TcpPort
import app.accrescent.server.domain.config.ApplicationConfig
import app.accrescent.server.domain.config.AuthnConfig
import app.accrescent.server.domain.config.GithubAuthnConfig
import app.accrescent.server.domain.config.ServerConfig
import app.accrescent.server.domain.config.SessionConfig
import app.accrescent.server.domain.config.SessionLifetime
import app.accrescent.server.domain.config.ShutdownTimeout
import app.accrescent.server.domain.ports.driven.cfgloader.ConfigLoadError
import app.accrescent.server.domain.ports.driven.cfgloader.ConfigLoader
import app.accrescent.server.domain.uri.HttpUri
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.smallrye.config.Converters
import io.smallrye.config.SmallRyeConfig
import io.smallrye.config.SmallRyeConfigBuilder
import org.eclipse.microprofile.config.spi.Converter
import java.net.InetAddress

private const val SERVER_ADDRESS_PROPERTY = "server.address"
private const val SERVER_PORT_PROPERTY = "server.port"
private const val SERVER_SHUTDOWN_TIMEOUT_PROPERTY = "server.shutdown-timeout"
private const val AUTHN_GITHUB_CLIENT_ID_PROPERTY = "authn.github.client-id"
private const val AUTHN_GITHUB_CLIENT_SECRET_PROPERTY = "authn.github.client-secret"
private const val AUTHN_GITHUB_REDIRECT_URI_PROPERTY = "authn.github.redirect-uri"
private const val AUTHN_SESSION_LIFETIME_PROPERTY = "authn.session.lifetime"

/**
 * A configuration loader which loads via
 * [SmallRye Config](https://smallrye.io/smallrye-config/Latest/).
 *
 * The loader sources config values from SmallRye Config's
 * [default config sources](https://smallrye.io/smallrye-config/Latest/config/getting-started/#config-sources).
 */
class SmallRyeConfigLoader : ConfigLoader {
    override fun loadConfig(): Either<ConfigLoadError, ApplicationConfig> = either {
        val config = SmallRyeConfigBuilder().addDefaultSources().build()

        ApplicationConfig(
            server = ServerConfig(
                address = loadProperty(
                    config,
                    SERVER_ADDRESS_PROPERTY,
                    INET_ADDRESS_LITERAL_CONVERTER,
                )
                    .bind(),
                port = loadProperty(config, SERVER_PORT_PROPERTY, TCP_PORT_CONVERTER).bind(),
                shutdownTimeout = loadProperty(
                    config,
                    SERVER_SHUTDOWN_TIMEOUT_PROPERTY,
                    SHUTDOWN_TIMEOUT_CONVERTER,
                )
                    .bind(),
            ),
            authn = AuthnConfig(
                github = GithubAuthnConfig(
                    clientId = loadProperty(
                        config,
                        AUTHN_GITHUB_CLIENT_ID_PROPERTY,
                        NON_EMPTY_STRING_CONVERTER,
                    )
                        .bind(),
                    clientSecret = loadProperty(
                        config,
                        AUTHN_GITHUB_CLIENT_SECRET_PROPERTY,
                        NON_EMPTY_STRING_CONVERTER,
                    )
                        .bind(),
                    redirectUri = loadProperty(
                        config,
                        AUTHN_GITHUB_REDIRECT_URI_PROPERTY,
                        HTTP_URI_CONVERTER,
                    )
                        .bind(),
                ),
                session = SessionConfig(
                    lifetime = loadProperty(
                        config,
                        AUTHN_SESSION_LIFETIME_PROPERTY,
                        SESSION_LIFETIME_CONVERTER,
                    )
                        .bind(),
                ),
            ),
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
        private val SHUTDOWN_TIMEOUT_CONVERTER = Converters.newEmptyValueConverter { value ->
            ShutdownTimeout.parse(value)
                .getOrNull()
                ?: throw IllegalArgumentException(
                    "\"$value\" is not an integer in the range [0, 60] followed by an \"s\" " +
                            "(e.g. \"30s\")"
                )
        }
        private val SESSION_LIFETIME_CONVERTER = Converters.newEmptyValueConverter { value ->
            SessionLifetime.parse(value)
                .getOrNull()
                ?: throw IllegalArgumentException(
                    "\"$value\" is not an integer in the range [1, 8760] followed by an \"h\" " +
                            "(e.g. \"336h\")"
                )
        }
        private val NON_EMPTY_STRING_CONVERTER = Converters.newEmptyValueConverter { value ->
            NonEmptyString.fromString(value)
                .getOrNull()
                ?: throw IllegalArgumentException("value must not be empty")
        }
        private val HTTP_URI_CONVERTER = Converters.newEmptyValueConverter { value ->
            HttpUri.fromString(value)
                .getOrNull()
                ?: throw IllegalArgumentException("\"$value\" is not a valid HTTP(S) URI")
        }
    }
}
