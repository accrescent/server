// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import app.accrescent.server.domain.ports.driving.api.console2.Organization
import build.buf.gen.accrescent.console.v1.getMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.organization
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageConvertersTest {
    @Test
    fun `toProto correctly converts GetMyOrganizationResponse`() {
        val domain = GetMyOrganizationResponse(
            Organization(
                id = "org1",
                publishedAppLimit = 1u,
                publishedAppCount = 1u,
                activeAppDraftLimit = 3u,
                activeAppDraftCount = 2u,
            )
        )
        val proto = getMyOrganizationResponse {
            organization = organization {
                id = "org1"
                publishedAppLimit = 1
                publishedAppCount = 1
                activeAppDraftLimit = 3
                activeAppDraftCount = 2
            }
        }

        assertEquals(proto, toProto(domain))
    }
}
