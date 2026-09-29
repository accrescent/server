// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.cfgloader

import app.accrescent.server.domain.config.ServerConfig
import arrow.core.Either

/**
 * A loader for the server configuration.
 */
interface ConfigLoader {
    /**
     * Loads the server configuration from a source or sources.
     */
    fun loadConfig(): Either<ConfigLoadError, ServerConfig>
}


/**
 * An error which can occur while attempting to load the server configuration.
 */
sealed interface ConfigLoadError {
    /**
     * A required config property was missing.
     *
     * @property name the name of the missing config property.
     */
    data class MissingProperty(val name: String) : ConfigLoadError

    /**
     * The value of a config property was invalid.
     *
     * @property name the name of the config property whose value is invalid.
     * @property message a message describing why the config property's value is invalid.
     */
    data class InvalidProperty(val name: String, val message: String) : ConfigLoadError
}
