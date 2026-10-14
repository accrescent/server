// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.cfgloader

import app.accrescent.server.core.Error
import app.accrescent.server.core.NonEmptyString
import app.accrescent.server.core.TcpPort
import app.accrescent.server.core.causedBy
import app.accrescent.server.domain.config.ApplicationConfig
import app.accrescent.server.domain.config.AuthnConfig
import app.accrescent.server.domain.config.DataStoreConfig
import app.accrescent.server.domain.config.GithubAuthnConfig
import app.accrescent.server.domain.config.ServerConfig
import app.accrescent.server.domain.config.SessionConfig
import app.accrescent.server.domain.config.SessionLifetime
import app.accrescent.server.domain.config.ShutdownTimeout
import app.accrescent.server.domain.ports.driven.cfgloader.ConfigLoadError
import app.accrescent.server.domain.ports.driven.cfgloader.ConfigLoader
import app.accrescent.server.domain.ports.driven.secretvault.SecretVault
import app.accrescent.server.domain.uri.HttpUri
import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.smallrye.config.Converters
import io.smallrye.config.SecretKeysHandler
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
private const val DATASTORE_BACKEND_PROPERTY = "datastore.backend"
private const val DATASTORE_POSTGRESQL_HOST_PROPERTY = "datastore.postgresql.host"
private const val DATASTORE_POSTGRESQL_PORT_PROPERTY = "datastore.postgresql.port"
private const val DATASTORE_POSTGRESQL_DATABASE_PROPERTY = "datastore.postgresql.database"
private const val DATASTORE_POSTGRESQL_USER_PROPERTY = "datastore.postgresql.user"
private const val DATASTORE_POSTGRESQL_PASSWORD_PROPERTY = "datastore.postgresql.password"

/**
 * A configuration loader which loads via
 * [SmallRye Config](https://smallrye.io/smallrye-config/Latest/).
 *
 * The loader sources config values from SmallRye Config's
 * [default config sources](https://smallrye.io/smallrye-config/Latest/config/getting-started/#config-sources).
 *
 * Config values can reference secrets stored in the vaults provided by [secretVaults] via
 * [secret expressions](https://smallrye.io/smallrye-config/Latest/config/secret-keys/), where
 * `handler` is a key into [secretVaults] and `value` is the secret name passed to
 * [SecretVault.getSecret].
 *
 * @param secretVaults the secret vaults to allow secret retrieval from, keyed by their secret
 * handler names.
 */
class SmallRyeConfigLoader(private val secretVaults: Map<String, SecretVault>) : ConfigLoader {
    override fun loadConfig(): Either<ConfigLoadError, ApplicationConfig> = either {
        val secretKeysHandlers = secretVaults
            .map { (name, vault) -> SecretVaultSecretKeysHandler(name, vault) }
            .toTypedArray()
        val config = SmallRyeConfigBuilder()
            .addDefaultSources()
            .addDefaultInterceptors()
            .withSecretKeysHandlers(*secretKeysHandlers)
            .build()

        ApplicationConfig(
            server = ServerConfig(
                address = loadProperty(
                    config,
                    SERVER_ADDRESS_PROPERTY,
                    INET_ADDRESS_LITERAL_CONVERTER,
                ),
                port = loadProperty(config, SERVER_PORT_PROPERTY, TCP_PORT_CONVERTER),
                shutdownTimeout = loadProperty(
                    config,
                    SERVER_SHUTDOWN_TIMEOUT_PROPERTY,
                    SHUTDOWN_TIMEOUT_CONVERTER,
                ),
            ),
            authn = AuthnConfig(
                github = GithubAuthnConfig(
                    clientId = loadProperty(
                        config,
                        AUTHN_GITHUB_CLIENT_ID_PROPERTY,
                        NON_EMPTY_STRING_CONVERTER,
                    ),
                    clientSecret = loadProperty(
                        config,
                        AUTHN_GITHUB_CLIENT_SECRET_PROPERTY,
                        NON_EMPTY_STRING_CONVERTER,
                    ),
                    redirectUri = loadProperty(
                        config,
                        AUTHN_GITHUB_REDIRECT_URI_PROPERTY,
                        HTTP_URI_CONVERTER,
                    ),
                ),
                session = SessionConfig(
                    lifetime = loadProperty(
                        config,
                        AUTHN_SESSION_LIFETIME_PROPERTY,
                        SESSION_LIFETIME_CONVERTER,
                    ),
                ),
            ),
            dataStore = loadDataStoreConfig(config),
        )
    }

    /**
     * Loads the data store configuration.
     *
     * Only the properties of the backend selected by [DATASTORE_BACKEND_PROPERTY] are loaded, so
     * the properties of other backends need not be set.
     */
    context(_: Raise<ConfigLoadError>)
    private fun loadDataStoreConfig(config: SmallRyeConfig): DataStoreConfig {
        val backend = loadProperty(config, DATASTORE_BACKEND_PROPERTY, DATASTORE_BACKEND_CONVERTER)
        return when (backend) {
            DataStoreBackend.MEMORY -> DataStoreConfig.Memory
            DataStoreBackend.POSTGRESQL -> DataStoreConfig.Postgresql(
                host = loadProperty(
                    config,
                    DATASTORE_POSTGRESQL_HOST_PROPERTY,
                    NON_EMPTY_STRING_CONVERTER,
                ),
                port = loadProperty(
                    config,
                    DATASTORE_POSTGRESQL_PORT_PROPERTY,
                    TCP_PORT_CONVERTER,
                ),
                database = loadProperty(
                    config,
                    DATASTORE_POSTGRESQL_DATABASE_PROPERTY,
                    NON_EMPTY_STRING_CONVERTER,
                ),
                user = loadProperty(
                    config,
                    DATASTORE_POSTGRESQL_USER_PROPERTY,
                    NON_EMPTY_STRING_CONVERTER,
                ),
                password = loadProperty(
                    config,
                    DATASTORE_POSTGRESQL_PASSWORD_PROPERTY,
                    NON_EMPTY_STRING_CONVERTER,
                ),
            )
        }
    }

    context(raise: Raise<ConfigLoadError>)
    private fun <T> loadProperty(config: SmallRyeConfig, name: String, converter: Converter<T>): T {
        return try {
            config.getValue(name, converter)
        } catch (e: IllegalArgumentException) {
            raise.raise(ConfigLoadError.InvalidProperty(name, e.message.toString()))
        } catch (_: NoSuchElementException) {
            raise.raise(ConfigLoadError.MissingProperty(name))
        } catch (e: SecretAccessException) {
            raise.raise(ConfigLoadError.SecretInaccessible(name).causedBy(e.error))
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
        private val DATASTORE_BACKEND_CONVERTER = Converters.newEmptyValueConverter { value ->
            DataStoreBackend.entries.find { it.propertyValue == value }
                ?: throw IllegalArgumentException(
                    "\"$value\" is not one of " +
                            DataStoreBackend.entries.joinToString { "\"${it.propertyValue}\"" }
                )
        }
    }
}

/**
 * A data store backend selectable via configuration.
 *
 * @property propertyValue the config property value which maps to this backend.
 */
private enum class DataStoreBackend(val propertyValue: String) {
    MEMORY("memory"),
    POSTGRESQL("postgresql"),
}

/**
 * A [SecretKeysHandler] which decodes secret expressions by retrieving secrets from a
 * [SecretVault].
 *
 * Secret names are passed to [SecretVault.getSecret]. Their corresponding values must be valid
 * UTF-8.
 *
 * Failures to retrieve or decode a secret are thrown as a [SecretAccessException].
 *
 * @param handlerName the name of this handler as used in secret expressions.
 * @param vault the vault to retrieve secrets from.
 */
private class SecretVaultSecretKeysHandler(
    private val handlerName: String,
    private val vault: SecretVault,
) : SecretKeysHandler {
    override fun getName(): String {
        return handlerName
    }

    override fun decode(secret: String): String {
        val value = vault.getSecret(secret).getOrElse { throw SecretAccessException(it) }

        return try {
            value.copyToByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw SecretAccessException(InvalidSecretEncodingError(secret))
        }
    }
}

/**
 * An exception indicating a secret referenced by a config property couldn't be accessed.
 *
 * @property error the error which caused the secret to be inaccessible.
 */
private class SecretAccessException(val error: Error) : RuntimeException(error.toString())

/**
 * A secret's value was not valid UTF-8.
 *
 * @property name the name of the secret.
 */
private class InvalidSecretEncodingError(val name: String) : Error() {
    override val message = "secret \"$name\" is not valid UTF-8"
}
