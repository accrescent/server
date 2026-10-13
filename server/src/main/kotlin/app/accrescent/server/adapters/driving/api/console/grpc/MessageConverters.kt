// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.getMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.organization
import build.buf.gen.accrescent.console.v1.GetMyOrganizationResponse as GetMyOrganizationResponseProto

fun toProto(response: GetMyOrganizationResponse): GetMyOrganizationResponseProto {
    return getMyOrganizationResponse {
        organization = organization {
            id = response.organization.id
            publishedAppLimit = response.organization.publishedAppLimit.toInt()
            publishedAppCount = response.organization.publishedAppCount.toInt()
            activeAppDraftLimit = response.organization.activeAppDraftLimit.toInt()
            activeAppDraftCount = response.organization.activeAppDraftCount.toInt()
        }
    }
}
