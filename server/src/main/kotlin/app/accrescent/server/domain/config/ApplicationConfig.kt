// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.config

import app.accrescent.server.core.NonEmptyString
import app.accrescent.server.core.TcpPort
import app.accrescent.server.domain.uri.HttpUri
import java.net.InetAddress

/**
 * The application configuration.
 *
 * @property server the HTTP server configuration.
 * @property authn the user authentication configuration.
 */
data class ApplicationConfig(
    val server: ServerConfig,
    val authn: AuthnConfig,
)

/**
 * A server configuration.
 *
 * @property address the address for the server to listen on.
 * @property port the port for the server to listen on.
 * @property shutdownTimeout the maximum amount of time to wait for in-flight requests to complete
 * when shutting down.
 */
data class ServerConfig(
    val address: InetAddress,
    val port: TcpPort,
    val shutdownTimeout: ShutdownTimeout,
)

/**
 * User authentication configuration.
 *
 * @property github user authentication config for GitHub authentication.
 * @property session user session configuration.
 */
data class AuthnConfig(val github: GithubAuthnConfig, val session: SessionConfig)

/**
 * GitHub user authentication configuration.
 *
 * @property clientId the OAuth2 client ID of the GitHub OAuth app to use for authentication.
 * @property clientSecret the OAuth2 client secret of the GitHub OAuth app to use for authentication.
 * @property redirectUri the OAuth2 redirect URI of the GitHub OAuth app to use for authentication.
 */
data class GithubAuthnConfig(
    val clientId: NonEmptyString,
    val clientSecret: NonEmptyString,
    val redirectUri: HttpUri,
)

/**
 * User session configuration.
 *
 * @property lifetime how long a user session remains valid after login.
 */
data class SessionConfig(val lifetime: SessionLifetime)
