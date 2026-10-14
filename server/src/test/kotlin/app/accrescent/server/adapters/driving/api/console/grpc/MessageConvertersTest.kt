// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.getMyOrganizationResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageConvertersTest {
    @Test
    fun `toProto correctly converts GetMyOrganizationResponse`() {
        val domain = GetMyOrganizationResponse("org1")
        val proto = getMyOrganizationResponse { organizationId = "org1" }

        assertEquals(proto, toProto(domain))
    }
}
