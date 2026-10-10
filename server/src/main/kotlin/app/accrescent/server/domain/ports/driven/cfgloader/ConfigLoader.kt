// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.cfgloader

import app.accrescent.server.core.Error
import app.accrescent.server.domain.config.ApplicationConfig
import arrow.core.Either

/**
 * A loader for the application configuration.
 */
interface ConfigLoader {
    /**
     * Loads the application configuration from a source or sources.
     */
    fun loadConfig(): Either<ConfigLoadError, ApplicationConfig>
}

/**
 * An error which can occur while attempting to load the server configuration.
 */
sealed class ConfigLoadError : Error() {
    /**
     * A required config property was missing.
     *
     * @property name the name of the missing config property.
     */
    class MissingProperty(val name: String) : ConfigLoadError() {
        override val message = "missing required configuration property \"$name\""
    }

    /**
     * The value of a config property was invalid.
     *
     * @property name the name of the config property whose value is invalid.
     * @property reason a message describing why the config property's value is invalid.
     */
    class InvalidProperty(val name: String, val reason: String) : ConfigLoadError() {
        override val message = "invalid value for configuration property \"$name\": $reason"
    }
}
