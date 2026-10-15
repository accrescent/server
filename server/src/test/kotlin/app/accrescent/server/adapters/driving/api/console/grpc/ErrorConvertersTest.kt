// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfError
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
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

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsGetOrganizationErrorTestCases")
    fun `toStatus correctly converts GetOrganizationError`(
        testCase: ToStatusCorrectlyConvertsGetOrganizationErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, status.code)
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsGetSelfErrorTestCases")
    fun `toStatus correctly converts GetSelfError`(
        testCase: ToStatusCorrectlyConvertsGetSelfErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, status.code)
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsServerErrorTestCases")
    fun `toStatus correctly converts ServerError`(
        testCase: ToStatusCorrectlyConvertsServerErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, status.code)
    }

    companion object {
        data class ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase(
            val error: GetMyOrganizationError,
            val expectedCode: Status.Code,
        )

        data class ToStatusCorrectlyConvertsGetOrganizationErrorTestCase(
            val error: GetOrganizationError,
            val expectedCode: Status.Code,
        )

        data class ToStatusCorrectlyConvertsGetSelfErrorTestCase(
            val error: GetSelfError,
            val expectedCode: Status.Code,
        )

        data class ToStatusCorrectlyConvertsServerErrorTestCase(
            val error: ServerError,
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
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsGetOrganizationErrorTestCases():
                List<ToStatusCorrectlyConvertsGetOrganizationErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsGetOrganizationErrorTestCase(
                    UnauthenticatedError(),
                    Status.Code.UNAUTHENTICATED,
                ),
                ToStatusCorrectlyConvertsGetOrganizationErrorTestCase(
                    InsufficientPermissionError(),
                    Status.Code.PERMISSION_DENIED,
                ),
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsGetSelfErrorTestCases():
                List<ToStatusCorrectlyConvertsGetSelfErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsGetSelfErrorTestCase(
                    UnauthenticatedError(),
                    Status.Code.UNAUTHENTICATED,
                ),
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsServerErrorTestCases():
                List<ToStatusCorrectlyConvertsServerErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsServerErrorTestCase(
                    ServerError.InternalServerError(),
                    Status.Code.INTERNAL,
                ),
                ToStatusCorrectlyConvertsServerErrorTestCase(
                    ServerError.ServiceUnavailableError(),
                    Status.Code.UNAVAILABLE,
                ),
            )
        }
    }
}
