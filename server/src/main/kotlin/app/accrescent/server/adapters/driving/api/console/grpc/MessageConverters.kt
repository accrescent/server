// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.getMyOrganizationResponse
import build.buf.gen.accrescent.console.v1.GetMyOrganizationResponse as GetMyOrganizationResponseProto

fun toProto(response: GetMyOrganizationResponse): GetMyOrganizationResponseProto {
    return getMyOrganizationResponse {
        organizationId = response.organizationId
    }
}
