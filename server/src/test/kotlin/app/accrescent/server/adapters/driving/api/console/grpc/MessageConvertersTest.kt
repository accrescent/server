// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.core.NonNegativeLong
import app.accrescent.server.core.unwrap
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftRequest
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationRequest
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfResponse
import app.accrescent.server.domain.ports.driving.api.console2.Organization
import app.accrescent.server.domain.ports.driving.api.console2.User
import app.accrescent.server.domain.ports.driving.api.console2.UserRole
import build.buf.gen.accrescent.console.v1.createAppDraftRequest
import build.buf.gen.accrescent.console.v1.createAppDraftResponse
import build.buf.gen.accrescent.console.v1.getMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.getOrganizationRequest
import build.buf.gen.accrescent.console.v1.getOrganizationResponse
import build.buf.gen.accrescent.console.v1.getSelfResponse
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
    fun `toDomain correctly converts GetOrganizationRequest`() {
        val proto = getOrganizationRequest { organizationId = "org1" }
        val domain = GetOrganizationRequest("org1")

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
                publishedAppLimit = NonNegativeLong.new(2).unwrap(),
                publishedAppCount = NonNegativeLong.new(1).unwrap(),
                activeAppDraftLimit = NonNegativeLong.new(4).unwrap(),
                activeAppDraftCount = NonNegativeLong.new(3).unwrap(),
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
}
