// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.authn.vertx

import app.accrescent.server.domain.authn.ExternalUserId
import app.accrescent.server.domain.config.AuthnConfig
import app.accrescent.server.domain.config.SessionLifetime
import app.accrescent.server.domain.crypto.Sha256Hash
import app.accrescent.server.domain.ports.driven.randomsource.RandomSource
import app.accrescent.server.domain.ports.driven.randomsource.RandomSourceResult
import app.accrescent.server.domain.ports.driving.api.authn.AuthenticationApi
import app.accrescent.server.domain.ports.driving.api.authn.CreateSessionError
import arrow.core.Some
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.singleOrNone
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.Cookie
import io.vertx.core.http.CookieSameSite
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.oauth2.OAuth2Auth
import io.vertx.ext.auth.oauth2.OAuth2AuthorizationURL
import io.vertx.ext.auth.oauth2.OAuth2FlowType
import io.vertx.ext.auth.oauth2.OAuth2Options
import io.vertx.ext.auth.oauth2.Oauth2Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64

//
// General constants
//

/**
 * The path prefix under which [AuthnServer] serves all of its endpoints.
 */
const val AUTHN_PATH_PREFIX = "/auth/"

// The most secure cookie name prefix. See
// https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie#cookie_prefixes
// for more information on what this accomplishes.
private const val COOKIE_NAME_PREFIX = "__Host-Http-"
private const val SESSION_COOKIE_NAME = "${COOKIE_NAME_PREFIX}session"
private const val POST_LOGIN_REDIRECT_PATH = "/"
private const val LOGIN_CANCELED_REDIRECT_PATH = "/"

// Minimum requirement set by https://datatracker.ietf.org/doc/html/rfc6749#section-10.10
private const val RANDOM_TOKEN_SIZE_BYTES = 16

// Minimum size recommended by https://datatracker.ietf.org/doc/html/rfc7636#section-7.1
private const val CODE_VERIFIER_SIZE_BYTES = 32
private const val LOGIN_COOKIE_MAX_AGE_SECONDS = 600L

//
// GitHub constants
//

private const val GITHUB_STATE_COOKIE_NAME = "${COOKIE_NAME_PREFIX}github-state"
private const val GITHUB_CODE_VERIFIER_COOKIE_NAME = "${COOKIE_NAME_PREFIX}github-code-verifier"
private const val GITHUB_CALLBACK_PATH = "${AUTHN_PATH_PREFIX}github/callback"
private const val GITHUB_LOGIN_PATH = "${AUTHN_PATH_PREFIX}github/login"

// Sourced from io.vertx.ext.auth.oauth2.providers.GithubAuth.create()
private const val GITHUB_AUTHORIZATION_PATH = "/oauth/authorize"
private const val GITHUB_SITE = "https://github.com/login"
private const val GITHUB_TOKEN_PATH = "/oauth/access_token"
private const val GITHUB_USER_INFO_PATH = "https://api.github.com/user"
private const val GITHUB_HTTP_CLIENT_NAME = "github"

/**
 * An HTTP server which serves the authentication endpoints.
 *
 * GitHub authentication is provided via the OAuth 2.0 authorization code with PKCE. No scopes are
 * requested.
 *
 * @param vertx the Vert.x instance to make requests to GitHub with.
 * @param context the coroutine context to run requests in.
 * @param authnConfig the user authentication config to use.
 * @param authenticationApi the authentication API to create sessions with.
 * @param randomSource the source of OAuth 2.0 states and PKCE code verifiers.
 * @param githubSite the GitHub login URL. Defaults to "https://github.com/login".
 * @param githubUserInfoPath the GitHub user info URL. Defaults to "https://api.github.com/user".
 */
class AuthnServer(
    vertx: Vertx,
    context: CoroutineContext,
    authnConfig: AuthnConfig,
    private val authenticationApi: AuthenticationApi,
    private val randomSource: RandomSource,
    githubSite: String = GITHUB_SITE,
    githubUserInfoPath: String = GITHUB_USER_INFO_PATH,
) : Handler<HttpServerRequest> {
    private val scope = CoroutineScope(context)
    private val sessionLifetime = authnConfig.session.lifetime
    private val githubRedirectUri = authnConfig.github.redirectUri.intoString()
    private val oauth2: OAuth2Auth = OAuth2Auth.create(
        vertx,
        OAuth2Options()
            .setClientId(authnConfig.github.clientId.value)
            .setClientSecret(authnConfig.github.clientSecret.value)
            .setSite(githubSite)
            .setTokenPath(GITHUB_TOKEN_PATH)
            .setAuthorizationPath(GITHUB_AUTHORIZATION_PATH)
            .setUserInfoPath(githubUserInfoPath)
            // The GitHub REST API requires a User-Agent header to be set. See
            // https://docs.github.com/en/rest/using-the-rest-api/getting-started-with-the-rest-api#user-agent.
            .setHeaders(JsonObject().put(HttpHeaders.USER_AGENT.toString(), "accrescent-server"))
            // Share one connection pool to GitHub across all AuthnServer instances
            .setHttpClientOptions(HttpClientOptions().setShared(true).setName(GITHUB_HTTP_CLIENT_NAME)),
    )

    override fun handle(request: HttpServerRequest) {
        val response = request.response()

        // Responses set and clear credentials, so they must never be cached
        response.putHeader(HttpHeaders.CACHE_CONTROL, "no-store")

        if (request.method() != HttpMethod.GET) {
            response
                .setStatusCode(HttpResponseStatus.METHOD_NOT_ALLOWED.code())
                .putHeader(HttpHeaders.ALLOW, HttpMethod.GET.name())
                .end()
            return
        }

        val path: String? = request.path()
        when (path) {
            GITHUB_LOGIN_PATH -> startGithubLogin(request)
            GITHUB_CALLBACK_PATH -> finishGithubLogin(request)
            else -> response.setStatusCode(HttpResponseStatus.NOT_FOUND.code()).end()
        }
    }

    private fun startGithubLogin(request: HttpServerRequest) {
        val response = request.response()

        val (state, codeVerifier) = either {
            val state = generateRandomValue(RANDOM_TOKEN_SIZE_BYTES).bind()
            val codeVerifier = generateRandomValue(CODE_VERIFIER_SIZE_BYTES).bind()
            state to codeVerifier
        }
            .getOrElse {
                logger.error("Failed to generate random value: {}", it)
                response.setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end()
                return
            }
        val codeChallenge = Sha256Hash.hash(codeVerifier.toByteArray())
            .digest()
            .copyToByteArray()
            .let(BASE64::encode)

        val authorizationUri = oauth2.authorizeURL(
            OAuth2AuthorizationURL()
                .setRedirectUri(githubRedirectUri)
                .setState(state)
                .setCodeChallenge(codeChallenge)
                // S256 is GitHub's only supported code challenge method. See
                // https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#1-request-a-users-github-identity.
                .setCodeChallengeMethod("S256"),
        )

        response
            .addSetCookie(loginCookie(GITHUB_STATE_COOKIE_NAME, state))
            .addSetCookie(loginCookie(GITHUB_CODE_VERIFIER_COOKIE_NAME, codeVerifier))
            .redirect(authorizationUri)
    }

    private fun finishGithubLogin(request: HttpServerRequest) {
        val response = request.response()

        val expectedState =
            request.cookies(GITHUB_STATE_COOKIE_NAME).singleOrNone().map(Cookie::getValue)
        val codeVerifier =
            request.cookies(GITHUB_CODE_VERIFIER_COOKIE_NAME).singleOrNone().map(Cookie::getValue)

        // Login attempts are single-use, so always clear the login cookies
        response
            .addSetCookie(expiredLoginCookie(GITHUB_STATE_COOKIE_NAME))
            .addSetCookie(expiredLoginCookie(GITHUB_CODE_VERIFIER_COOKIE_NAME))

        // This method throws IllegalArgumentException when decoding a malformed query string, so we
        // should handle it cleanly
        val params = try {
            request.params()
        } catch (_: IllegalArgumentException) {
            response.setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end()
            return
        }
        val state = params.getAll("state").singleOrNone()
        if (
            expectedState !is Some
            || codeVerifier !is Some
            || state !is Some
            // Compare in constant time to avoid timing attacks
            || !MessageDigest.isEqual(state.value.toByteArray(), expectedState.value.toByteArray())
        ) {
            response.setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end()
            return
        }

        // GitHub redirects with an error instead of a code when authorization fails. See
        // https://docs.github.com/en/apps/oauth-apps/maintaining-oauth-apps/troubleshooting-authorization-request-errors.
        val errors = params.getAll("error")
        if (errors.isNotEmpty()) {
            if (errors == listOf("access_denied")) {
                // The user declined authorization
                response.redirect(LOGIN_CANCELED_REDIRECT_PATH)
            } else {
                // Ignore other errors for now since they're user-supplied
                response.setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end()
            }
            return
        }

        val code = params.getAll("code").singleOrNone()
        if (code !is Some) {
            response.setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end()
            return
        }

        val credentials = Oauth2Credentials()
            .setFlow(OAuth2FlowType.AUTH_CODE)
            .setCode(code.value)
            .setCodeVerifier(codeVerifier.value)
            // RFC 6749 requires sending the redirect URI here if it was included in the
            // authorization request. See https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.3.
            .setRedirectUri(githubRedirectUri)
        oauth2.authenticate(credentials).compose(oauth2::userInfo).onComplete(
            { userInfo -> scope.launch { completeGithubLogin(response, userInfo) } },
            {
                logger.error("Failed to authenticate GitHub user", it)
                // Some errors here are actually client errors, but we can't reliably distinguish
                // them due to limitations in Vert.x's API, so treat them all as upstream failures
                response.setStatusCode(HttpResponseStatus.BAD_GATEWAY.code()).end()
            },
        )
    }

    private suspend fun completeGithubLogin(response: HttpServerResponse, userInfo: JsonObject) {
        // GitHub user IDs are Longs (see ExternalUserId.Github). We use getValue() instead of
        // getLong() here since getLong() silently converts doubles, wraps around BigIntegers, etc.
        val userId = when (val id = userInfo.getValue("id")) {
            is Int -> id.toLong()
            is Long -> id
            else -> {
                logger.error("GitHub user info has an invalid ID")
                response.setStatusCode(HttpResponseStatus.BAD_GATEWAY.code()).end()
                return
            }
        }

        val session = authenticationApi.createSession(ExternalUserId.Github(userId)).getOrElse {
            logger.error("Failed to create session: {}", it)
            val status = when (it) {
                is CreateSessionError.InternalServerError -> HttpResponseStatus.INTERNAL_SERVER_ERROR
                is CreateSessionError.ServiceUnavailableError -> HttpResponseStatus.SERVICE_UNAVAILABLE
            }
            response.setStatusCode(status.code()).end()
            return
        }

        response
            .addSetCookie(sessionCookie(session.sessionId, sessionLifetime))
            .redirect(POST_LOGIN_REDIRECT_PATH)
    }

    private fun generateRandomValue(size: Int): RandomSourceResult<String> {
        val bytes = ByteArray(size)
        return randomSource.fillRandomBytes(bytes).map { BASE64.encode(bytes) }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(AuthnServer::class.java)

        // PKCE requires unpadded base64url for both the code verifier and code challenge. See
        // https://datatracker.ietf.org/doc/html/rfc7636#section-4.1 and
        // https://datatracker.ietf.org/doc/html/rfc7636#appendix-A.
        private val BASE64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}

private fun hostHttpCookie(name: String, value: String): Cookie {
    return Cookie.cookie(name, value)
        .setPath("/")
        .setSecure(true)
        .setHttpOnly(true)
}

private fun loginCookie(name: String, value: String): Cookie {
    return hostHttpCookie(name, value)
        // Use SameSite=Lax since it's the strictest setting that works here. SameSite=Strict
        // doesn't since GitHub redirects to the callback with a cross-site navigation.
        .setSameSite(CookieSameSite.LAX)
        .setMaxAge(LOGIN_COOKIE_MAX_AGE_SECONDS)
}

private fun expiredLoginCookie(name: String): Cookie {
    return loginCookie(name, "").setMaxAge(0)
}

private fun sessionCookie(sessionId: String, lifetime: SessionLifetime): Cookie {
    return hostHttpCookie(SESSION_COOKIE_NAME, sessionId)
        .setSameSite(CookieSameSite.STRICT)
        .setMaxAge(lifetime.value.inWholeSeconds)
}

/**
 * Adds a Set-Cookie header to this response.
 *
 * This method exists because [HttpServerResponse.addCookie] always adds an Expires attribute
 * alongside Max-Age using the system clock, which is non-deterministic and thus makes testing more
 * difficult. We don't need Expires since Max-Age is supported by every not-entirely-archaic
 * browser, so this method simply doesn't encode it.
 *
 * @param cookie the cookie to send in the response.
 * @return the updated response.
 */
private fun HttpServerResponse.addSetCookie(cookie: Cookie): HttpServerResponse {
    val value = buildList {
        add("${cookie.name}=${cookie.value}")
        // Vert.x uses Long.MIN_VALUE to represent an unset Max-Age
        if (cookie.maxAge != Long.MIN_VALUE) add("Max-Age=${cookie.maxAge}")
        if (cookie.path != null) add("Path=${cookie.path}")
        if (cookie.domain != null) add("Domain=${cookie.domain}")
        if (cookie.isSecure) add("Secure")
        if (cookie.isHttpOnly) add("HttpOnly")
        if (cookie.sameSite != null) add("SameSite=${cookie.sameSite}")
    }
        .joinToString("; ")
    headers().add(HttpHeaders.SET_COOKIE, value)

    return this
}

private fun HttpServerResponse.redirect(location: String) {
    setStatusCode(HttpResponseStatus.FOUND.code())
        .putHeader(HttpHeaders.LOCATION, location)
        .end()
}
