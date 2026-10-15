// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.config

import app.accrescent.server.core.NonEmptyString
import app.accrescent.server.core.TcpPort
import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.uri.HttpUri
import java.net.InetAddress

/**
 * The application configuration.
 *
 * @property server the HTTP server configuration.
 * @property authn the user authentication configuration.
 * @property authz the user authorization configuration.
 * @property dataStore the data store configuration.
 */
data class ApplicationConfig(
    val server: ServerConfig,
    val authn: AuthnConfig,
    val authz: AuthzConfig,
    val dataStore: DataStoreConfig,
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

/**
 * User authorization configuration.
 *
 * @property adminExternalUserId the external identity of the administrator user.
 */
data class AuthzConfig(val adminExternalUserId: ExternalUserId)

/**
 * Data store configuration.
 */
sealed class DataStoreConfig {
    /**
     * Configuration for a data store held entirely in memory.
     *
     * All data is lost when the server exits, so this backend is intended only for development and
     * testing.
     */
    data object Memory : DataStoreConfig()

    /**
     * Configuration for a data store backed by a PostgreSQL database.
     *
     * @property endpoint the endpoint of the PostgreSQL server and the means of authenticating to
     * it.
     * @property database the name of the database to connect to.
     */
    data class Postgresql(
        val endpoint: PostgresqlEndpoint,
        val database: NonEmptyString,
    ) : DataStoreConfig()
}

/**
 * The endpoint of a PostgreSQL server and the means of authenticating to it.
 */
sealed class PostgresqlEndpoint {
    /**
     * A PostgreSQL server connected to directly by its address.
     *
     * @property host the host name or IP address of the PostgreSQL server.
     * @property port the port of the PostgreSQL server.
     * @property credentials the credentials to authenticate with.
     */
    data class Direct(
        val host: NonEmptyString,
        val port: TcpPort,
        val credentials: PostgresqlPasswordCredentials,
    ) : PostgresqlEndpoint()

    /**
     * A Cloud SQL for PostgreSQL instance connected to via the
     * [Cloud SQL Java Connector](https://docs.cloud.google.com/sql/docs/postgres/connect-connectors#java).
     *
     * @property connectionName the instance connection name of the form
     * `<project>:<region>:<instance>`.
     * @property authn the means of authenticating to the instance.
     */
    data class CloudSqlInstance(
        val connectionName: NonEmptyString,
        val authn: CloudSqlAuthn,
    ) : PostgresqlEndpoint()
}

/**
 * A means of authenticating to a Cloud SQL instance.
 */
sealed class CloudSqlAuthn {
    /**
     * Built-in database authentication with a password.
     *
     * @property credentials the credentials to authenticate with.
     */
    data class Password(val credentials: PostgresqlPasswordCredentials) : CloudSqlAuthn()

    /**
     * [IAM database authentication](https://docs.cloud.google.com/sql/docs/postgres/iam-authentication).
     *
     * Authentication occurs via
     * [Application Default Credentials](https://docs.cloud.google.com/docs/authentication/application-default-credentials).
     *
     * @property user the username of the IAM user to authenticate as. For a user account, this is
     * the account's email address. For a service account, this is the service account's email
     * address with the `.gserviceaccount.com` suffix removed (e.g., `my-sa@my-project.iam`).
     */
    data class Iam(val user: NonEmptyString) : CloudSqlAuthn()
}

/**
 * Credentials for PostgreSQL built-in password authentication.
 *
 * @property user the name of the PostgreSQL user to authenticate as.
 * @property password the password of [user].
 */
data class PostgresqlPasswordCredentials(val user: NonEmptyString, val password: NonEmptyString)
