// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.authn

sealed class ExternalUserId {
    /**
     * A GitHub user.
     *
     * @property userId the user's unique account ID.
     */
    // According to
    // https://github.com/github/rest-api-description/blob/main/descriptions/api.github.com/api.github.com.2026-03-10.yaml
    // lines 85475-85477, a GitHub user ID is an int64, which is equivalent to a Long.
    data class Github(val userId: Long) : ExternalUserId()
}
