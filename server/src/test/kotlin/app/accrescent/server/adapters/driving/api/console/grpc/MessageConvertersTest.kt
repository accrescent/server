// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.core.NonNegativeInt
import app.accrescent.server.core.PositiveInt
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.android.ApplicationId
import app.accrescent.server.domain.android.VersionName
import app.accrescent.server.domain.ports.driving.api.console2.AppDraftSummary
import app.accrescent.server.domain.ports.driving.api.console2.AppPackageSummary
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftResponse
import app.accrescent.server.domain.ports.driving.api.console2.DeleteAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfResponse
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesRequest
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesResponse
import app.accrescent.server.domain.ports.driving.api.console2.Organization
import app.accrescent.server.domain.ports.driving.api.console2.User
import app.accrescent.server.domain.ports.driving.api.console2.UserRole
import app.accrescent.server.domain.uri.HttpUri
import arrow.core.Some
import build.buf.gen.accrescent.console.v1.appDraftSummary
import build.buf.gen.accrescent.console.v1.createAppDraftRequest
import build.buf.gen.accrescent.console.v1.createAppDraftResponse
import build.buf.gen.accrescent.console.v1.deleteAppDraftRequest
import build.buf.gen.accrescent.console.v1.getMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.getOrganizationRequest
import build.buf.gen.accrescent.console.v1.getOrganizationResponse
import build.buf.gen.accrescent.console.v1.getSelfResponse
import build.buf.gen.accrescent.console.v1.listAppDraftSummariesRequest
import build.buf.gen.accrescent.console.v1.listAppDraftSummariesResponse
import build.buf.gen.accrescent.console.v1.organization
import build.buf.gen.accrescent.console.v1.user
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import build.buf.gen.accrescent.console.v1.UserRole as UserRoleProto

class MessageConvertersTest {
    @Test
    fun `toDomain correctly converts CreateAppDraftRequest`() {
        val proto = createAppDraftRequest { organizationId = "org1" }
        val domain = CreateAppDraftRequest("org1")

        assertEquals(domain, toDomain(proto))
    }

    @Test
    fun `toDomain correctly converts DeleteAppDraftRequest`() {
        val proto = deleteAppDraftRequest { appDraftId = "draft1" }
        val domain = DeleteAppDraftRequest("draft1")

        assertEquals(domain, toDomain(proto))
    }

    @Test
    fun `toDomain correctly converts GetOrganizationRequest`() {
        val proto = getOrganizationRequest { organizationId = "org1" }
        val domain = GetOrganizationRequest("org1")

        assertEquals(domain, toDomain(proto))
    }

    @Test
    fun `toDomain correctly converts ListAppDraftSummariesRequest`() {
        val proto = listAppDraftSummariesRequest {
            organizationId = "org1"
            pageSize = 10
            pageToken = "token1"
        }
        val domain = ListAppDraftSummariesRequest(
            organizationId = "org1",
            pageSize = Some(PositiveInt.new(10).unwrap()),
            pageToken = Some("token1"),
        )

        assertEquals(domain, toDomain(proto))
    }

    @Test
    fun `toProto correctly converts CreateAppDraftResponse`() {
        val domain = CreateAppDraftResponse("draft1")
        val proto = createAppDraftResponse { appDraftId = "draft1" }

        assertEquals(proto, toProto(domain))
    }

    @Test
    fun `toProto correctly converts GetMyOrganizationResponse`() {
        val domain = GetMyOrganizationResponse("org1")
        val proto = getMyOrganizationResponse { organizationId = "org1" }

        assertEquals(proto, toProto(domain))
    }

    @Test
    fun `toProto correctly converts GetOrganizationResponse`() {
        val domain = GetOrganizationResponse(
            Organization(
                id = "org1",
                publishedAppLimit = NonNegativeInt.new(2).unwrap(),
                publishedAppCount = NonNegativeInt.new(1).unwrap(),
                activeAppDraftLimit = NonNegativeInt.new(4).unwrap(),
                activeAppDraftCount = NonNegativeInt.new(3).unwrap(),
            )
        )
        val proto = getOrganizationResponse {
            organization = organization {
                id = "org1"
                publishedAppLimit = 2
                publishedAppCount = 1
                activeAppDraftLimit = 4
                activeAppDraftCount = 3
            }
        }

        assertEquals(proto, toProto(domain))
    }

    @Test
    fun `toProto correctly converts GetSelfResponse`() {
        val domain = GetSelfResponse(User("user1", setOf(UserRole.REVIEWER, UserRole.PUBLISHER)))
        val proto = getSelfResponse {
            user = user {
                id = "user1"
                roles += listOf(UserRoleProto.USER_ROLE_REVIEWER, UserRoleProto.USER_ROLE_PUBLISHER)
            }
        }

        assertEquals(proto, toProto(domain))
    }

    @Test
    fun `toProto correctly converts ListAppDraftSummariesResponse`() {
        val domain = ListAppDraftSummariesResponse(
            appDraftSummaries = listOf(
                AppDraftSummary(
                    id = "draft1",
                    name = Some("Example"),
                    iconUri = Some(HttpUri.fromString("https://example.com/icon.png").unwrap()),
                    appPackage = Some(
                        AppPackageSummary(
                            androidApplicationId = ApplicationId
                                .fromString("com.example.app")
                                .unwrap(),
                            versionName = VersionName.fromString("1.0.0").unwrap(),
                        ),
                    ),
                ),
            ),
            nextPageToken = Some("token1"),
        )
        val proto = listAppDraftSummariesResponse {
            appDraftSummaries += appDraftSummary {
                id = "draft1"
                name = "Example"
                iconUri = "https://example.com/icon.png"
                versionName = "1.0.0"
                androidApplicationId = "com.example.app"
            }
            nextPageToken = "token1"
        }

        assertEquals(proto, toProto(domain))
    }
}
