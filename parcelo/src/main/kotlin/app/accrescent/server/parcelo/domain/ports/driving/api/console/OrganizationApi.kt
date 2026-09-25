// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.ports.driving.api.console

import arrow.core.Either

interface OrganizationApi {
    /**
     * Gets the organization the calling user belongs to.
     */
    fun getMyOrganization(
        context: CallContext,
    ): Either<GetMyOrganizationError, GetMyOrganizationResponse>
}
