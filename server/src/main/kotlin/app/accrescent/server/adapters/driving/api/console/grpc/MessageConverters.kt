// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationResponse
import build.buf.gen.accrescent.console.v1.getMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.getOrganizationResponse
import build.buf.gen.accrescent.console.v1.organization
import build.buf.gen.accrescent.console.v1.GetMyOrganizationResponse as GetMyOrganizationResponseProto
import build.buf.gen.accrescent.console.v1.GetOrganizationRequest as GetOrganizationRequestProto
import build.buf.gen.accrescent.console.v1.GetOrganizationResponse as GetOrganizationResponseProto

fun toDomain(request: GetOrganizationRequestProto): GetOrganizationRequest {
    return GetOrganizationRequest(request.organizationId)
}

fun toProto(response: GetMyOrganizationResponse): GetMyOrganizationResponseProto {
    return getMyOrganizationResponse {
        organizationId = response.organizationId
    }
}

fun toProto(response: GetOrganizationResponse): GetOrganizationResponseProto {
    return getOrganizationResponse {
        organization = organization {
            id = response.organization.id
            publishedAppLimit = response.organization.publishedAppLimit.toInt()
            publishedAppCount = response.organization.publishedAppCount.toInt()
            activeAppDraftLimit = response.organization.activeAppDraftLimit.toInt()
            activeAppDraftCount = response.organization.activeAppDraftCount.toInt()
        }
    }
}
