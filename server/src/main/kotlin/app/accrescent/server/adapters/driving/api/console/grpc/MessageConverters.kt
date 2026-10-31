// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.core.PositiveInt
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.appstore.AppName
import app.accrescent.server.domain.appstore.AppShortDescription
import app.accrescent.server.domain.ports.driving.api.console2.AppDraftSummary
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftListingTextRequest
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftListingTextResponse
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftResponse
import app.accrescent.server.domain.ports.driving.api.console2.DeleteAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetAppDraftDetailsRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetAppDraftDetailsResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfResponse
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesRequest
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesResponse
import app.accrescent.server.domain.ports.driving.api.console2.UserRole
import arrow.core.None
import arrow.core.Some
import arrow.core.some
import build.buf.gen.accrescent.console.v1.appDraftListingText
import build.buf.gen.accrescent.console.v1.appDraftSummary
import build.buf.gen.accrescent.console.v1.createAppDraftListingTextResponse
import build.buf.gen.accrescent.console.v1.createAppDraftResponse
import build.buf.gen.accrescent.console.v1.getAppDraftDetailsResponse
import build.buf.gen.accrescent.console.v1.getMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.getOrganizationResponse
import build.buf.gen.accrescent.console.v1.getSelfResponse
import build.buf.gen.accrescent.console.v1.listAppDraftSummariesResponse
import build.buf.gen.accrescent.console.v1.organization
import build.buf.gen.accrescent.console.v1.user
import build.buf.gen.accrescent.console.v1.AppDraftSummary as AppDraftSummaryProto
import build.buf.gen.accrescent.console.v1.CreateAppDraftListingTextRequest as CreateAppDraftListingTextRequestProto
import build.buf.gen.accrescent.console.v1.CreateAppDraftListingTextResponse as CreateAppDraftListingTextResponseProto
import build.buf.gen.accrescent.console.v1.CreateAppDraftRequest as CreateAppDraftRequestProto
import build.buf.gen.accrescent.console.v1.CreateAppDraftResponse as CreateAppDraftResponseProto
import build.buf.gen.accrescent.console.v1.DeleteAppDraftRequest as DeleteAppDraftRequestProto
import build.buf.gen.accrescent.console.v1.GetAppDraftDetailsRequest as GetAppDraftDetailsRequestProto
import build.buf.gen.accrescent.console.v1.GetAppDraftDetailsResponse as GetAppDraftDetailsResponseProto
import build.buf.gen.accrescent.console.v1.GetMyOrganizationResponse as GetMyOrganizationResponseProto
import build.buf.gen.accrescent.console.v1.GetOrganizationRequest as GetOrganizationRequestProto
import build.buf.gen.accrescent.console.v1.GetOrganizationResponse as GetOrganizationResponseProto
import build.buf.gen.accrescent.console.v1.GetSelfResponse as GetSelfResponseProto
import build.buf.gen.accrescent.console.v1.ListAppDraftSummariesRequest as ListAppDraftSummariesRequestProto
import build.buf.gen.accrescent.console.v1.ListAppDraftSummariesResponse as ListAppDraftSummariesResponseProto
import build.buf.gen.accrescent.console.v1.UserRole as UserRoleProto

fun toDomain(request: CreateAppDraftRequestProto): CreateAppDraftRequest {
    return CreateAppDraftRequest(request.organizationId)
}

fun toDomain(request: CreateAppDraftListingTextRequestProto): CreateAppDraftListingTextRequest {
    return CreateAppDraftListingTextRequest(
        appDraftId = request.appDraftId,
        // Request validation guarantees the name and short description are valid
        name = AppName.fromString(request.name).unwrap(),
        shortDescription = AppShortDescription.fromString(request.shortDescription).unwrap(),
    )
}

fun toDomain(request: DeleteAppDraftRequestProto): DeleteAppDraftRequest {
    return DeleteAppDraftRequest(request.appDraftId)
}

fun toDomain(request: GetAppDraftDetailsRequestProto): GetAppDraftDetailsRequest {
    return GetAppDraftDetailsRequest(request.appDraftId)
}

fun toDomain(request: GetOrganizationRequestProto): GetOrganizationRequest {
    return GetOrganizationRequest(request.organizationId)
}

fun toDomain(request: ListAppDraftSummariesRequestProto): ListAppDraftSummariesRequest {
    return ListAppDraftSummariesRequest(
        organizationId = request.organizationId,
        pageSize = if (request.hasPageSize()) {
            // Request validation guarantees the page size is positive if set
            PositiveInt.new(request.pageSize).unwrap().some()
        } else {
            None
        },
        pageToken = if (request.hasPageToken()) Some(request.pageToken) else None,
    )
}

fun toProto(response: CreateAppDraftResponse): CreateAppDraftResponseProto {
    return createAppDraftResponse {
        appDraftId = response.appDraftId
    }
}

fun toProto(response: CreateAppDraftListingTextResponse): CreateAppDraftListingTextResponseProto {
    return createAppDraftListingTextResponse {
        etag = response.etag
    }
}

fun toProto(response: GetAppDraftDetailsResponse): GetAppDraftDetailsResponseProto {
    return getAppDraftDetailsResponse {
        response.listingText.onSome {
            listingText = appDraftListingText {
                name = it.name
                shortDescription = it.shortDescription
            }
            listingTextEtag = it.etag
        }
    }
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

fun toProto(response: ListAppDraftSummariesResponse): ListAppDraftSummariesResponseProto {
    return listAppDraftSummariesResponse {
        appDraftSummaries += response.appDraftSummaries.map(::toProto)
        response.nextPageToken.onSome { nextPageToken = it }
    }
}

private fun toProto(summary: AppDraftSummary): AppDraftSummaryProto {
    return appDraftSummary {
        id = summary.id
        summary.name.onSome { name = it }
        summary.iconUri.onSome { iconUri = it.intoString() }
        summary.appPackage.onSome {
            versionName = it.versionName.value
            androidApplicationId = it.androidApplicationId.value
        }
    }
}

fun toProto(role: UserRole): UserRoleProto {
    return when (role) {
        UserRole.REVIEWER -> UserRoleProto.USER_ROLE_REVIEWER
        UserRole.PUBLISHER -> UserRoleProto.USER_ROLE_PUBLISHER
    }
}
