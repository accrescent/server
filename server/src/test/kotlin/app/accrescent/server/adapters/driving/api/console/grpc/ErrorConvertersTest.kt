// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.ActiveAppDraftLimitExceededError
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.DeleteAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfError
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console2.ServerError
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import com.google.rpc.Code
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ErrorConvertersTest {
    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsCreateAppDraftErrorTestCases")
    fun `toStatus correctly converts CreateAppDraftError`(
        testCase: ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsDeleteAppDraftErrorTestCases")
    fun `toStatus correctly converts DeleteAppDraftError`(
        testCase: ToStatusCorrectlyConvertsDeleteAppDraftErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsGetMyOrganizationErrorTestCases")
    fun `toStatus correctly converts GetMyOrganizationError`(
        testCase: ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsGetOrganizationErrorTestCases")
    fun `toStatus correctly converts GetOrganizationError`(
        testCase: ToStatusCorrectlyConvertsGetOrganizationErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsGetSelfErrorTestCases")
    fun `toStatus correctly converts GetSelfError`(
        testCase: ToStatusCorrectlyConvertsGetSelfErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsServerErrorTestCases")
    fun `toStatus correctly converts ServerError`(
        testCase: ToStatusCorrectlyConvertsServerErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
    }

    companion object {
        data class ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase(
            val error: CreateAppDraftError,
            val expectedCode: Code,
        )

        data class ToStatusCorrectlyConvertsDeleteAppDraftErrorTestCase(
            val error: DeleteAppDraftError,
            val expectedCode: Code,
        )

        data class ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase(
            val error: GetMyOrganizationError,
            val expectedCode: Code,
        )

        data class ToStatusCorrectlyConvertsGetOrganizationErrorTestCase(
            val error: GetOrganizationError,
            val expectedCode: Code,
        )

        data class ToStatusCorrectlyConvertsGetSelfErrorTestCase(
            val error: GetSelfError,
            val expectedCode: Code,
        )

        data class ToStatusCorrectlyConvertsServerErrorTestCase(
            val error: ServerError,
            val expectedCode: Code,
        )

        @JvmStatic
        private fun toStatusCorrectlyConvertsCreateAppDraftErrorTestCases():
                List<ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase(
                    UnauthenticatedError(),
                    Code.UNAUTHENTICATED,
                ),
                ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase(
                    InsufficientPermissionError(),
                    Code.PERMISSION_DENIED,
                ),
                ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase(
                    ActiveAppDraftLimitExceededError(),
                    Code.RESOURCE_EXHAUSTED,
                ),
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsDeleteAppDraftErrorTestCases():
                List<ToStatusCorrectlyConvertsDeleteAppDraftErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsDeleteAppDraftErrorTestCase(
                    UnauthenticatedError(),
                    Code.UNAUTHENTICATED,
                ),
                ToStatusCorrectlyConvertsDeleteAppDraftErrorTestCase(
                    InsufficientPermissionError(),
                    Code.PERMISSION_DENIED,
                ),
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsGetMyOrganizationErrorTestCases():
                List<ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase(
                    UnauthenticatedError(),
                    Code.UNAUTHENTICATED,
                ),
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsGetOrganizationErrorTestCases():
                List<ToStatusCorrectlyConvertsGetOrganizationErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsGetOrganizationErrorTestCase(
                    UnauthenticatedError(),
                    Code.UNAUTHENTICATED,
                ),
                ToStatusCorrectlyConvertsGetOrganizationErrorTestCase(
                    InsufficientPermissionError(),
                    Code.PERMISSION_DENIED,
                ),
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsGetSelfErrorTestCases():
                List<ToStatusCorrectlyConvertsGetSelfErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsGetSelfErrorTestCase(
                    UnauthenticatedError(),
                    Code.UNAUTHENTICATED,
                ),
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsServerErrorTestCases():
                List<ToStatusCorrectlyConvertsServerErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsServerErrorTestCase(
                    ServerError.InternalServerError(),
                    Code.INTERNAL,
                ),
                ToStatusCorrectlyConvertsServerErrorTestCase(
                    ServerError.ServiceUnavailableError(),
                    Code.UNAVAILABLE,
                ),
            )
        }
    }
}
