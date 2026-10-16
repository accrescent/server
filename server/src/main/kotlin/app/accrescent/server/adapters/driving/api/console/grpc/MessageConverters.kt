// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfResponse
import app.accrescent.server.domain.ports.driving.api.console2.UserRole
import build.buf.gen.accrescent.console.v1.getMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.getOrganizationResponse
import build.buf.gen.accrescent.console.v1.getSelfResponse
import build.buf.gen.accrescent.console.v1.organization
import build.buf.gen.accrescent.console.v1.user
import build.buf.gen.accrescent.console.v1.GetMyOrganizationResponse as GetMyOrganizationResponseProto
import build.buf.gen.accrescent.console.v1.GetOrganizationRequest as GetOrganizationRequestProto
import build.buf.gen.accrescent.console.v1.GetOrganizationResponse as GetOrganizationResponseProto
import build.buf.gen.accrescent.console.v1.GetSelfResponse as GetSelfResponseProto
import build.buf.gen.accrescent.console.v1.UserRole as UserRoleProto

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
            publishedAppLimit = response.organization.publishedAppLimit.value
            publishedAppCount = response.organization.publishedAppCount.value
            activeAppDraftLimit = response.organization.activeAppDraftLimit.value
            activeAppDraftCount = response.organization.activeAppDraftCount.value
        }
    }
}

fun toProto(response: GetSelfResponse): GetSelfResponseProto {
    return getSelfResponse {
        user = user {
            id = response.user.id
            roles += response.user.roles.map(::toProto)
        }
    }
}

fun toProto(role: UserRole): UserRoleProto {
    return when (role) {
        UserRole.REVIEWER -> UserRoleProto.USER_ROLE_REVIEWER
        UserRole.PUBLISHER -> UserRoleProto.USER_ROLE_PUBLISHER
    }
}
