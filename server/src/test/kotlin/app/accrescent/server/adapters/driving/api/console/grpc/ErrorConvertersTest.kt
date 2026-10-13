// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.ServerError
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import io.grpc.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ErrorConvertersTest {
    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsGetMyOrganizationErrorTestCases")
    fun `toStatus correctly converts GetMyOrganizationError`(
        testCase: ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, status.code)
    }

    companion object {
        data class ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase(
            val error: GetMyOrganizationError,
            val expectedCode: Status.Code,
        )

        @JvmStatic
        private fun toStatusCorrectlyConvertsGetMyOrganizationErrorTestCases():
                List<ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase(
                    UnauthenticatedError(),
                    Status.Code.UNAUTHENTICATED,
                ),
                ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase(
                    ServerError.InternalServerError(),
                    Status.Code.INTERNAL,
                ),
                ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase(
                    ServerError.ServiceUnavailableError(),
                    Status.Code.UNAVAILABLE,
                ),
            )
        }
    }
}
