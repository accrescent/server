// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import app.accrescent.server.domain.ports.driving.api.console2.ActiveAppDraftLimitExceededError
import app.accrescent.server.domain.ports.driving.api.console2.AppDraftListingTextAlreadyExistsError
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.CreateAppDraftListingTextError
import app.accrescent.server.domain.ports.driving.api.console2.DeleteAppDraftError
import app.accrescent.server.domain.ports.driving.api.console2.GetMyOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetOrganizationError
import app.accrescent.server.domain.ports.driving.api.console2.GetSelfError
import app.accrescent.server.domain.ports.driving.api.console2.InsufficientPermissionError
import app.accrescent.server.domain.ports.driving.api.console2.InvalidPageTokenError
import app.accrescent.server.domain.ports.driving.api.console2.ListAppDraftSummariesError
import app.accrescent.server.domain.ports.driving.api.console2.ServerError
import app.accrescent.server.domain.ports.driving.api.console2.UnauthenticatedError
import build.buf.gen.accrescent.console.v1.ErrorReason
import com.google.rpc.Code
import com.google.rpc.ErrorInfo
import com.google.rpc.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

private const val ERROR_DOMAIN = "console.accrescent.app"

class ErrorConvertersTest {
    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsCreateAppDraftErrorTestCases")
    fun `toStatus correctly converts CreateAppDraftError`(
        testCase: ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
        assertEquals(testCase.expectedErrorInfo, unpackErrorInfo(status))
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsCreateAppDraftListingTextErrorTestCases")
    fun `toStatus correctly converts CreateAppDraftListingTextError`(
        testCase: ToStatusCorrectlyConvertsCreateAppDraftListingTextErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
        assertEquals(testCase.expectedErrorInfo, unpackErrorInfo(status))
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsDeleteAppDraftErrorTestCases")
    fun `toStatus correctly converts DeleteAppDraftError`(
        testCase: ToStatusCorrectlyConvertsDeleteAppDraftErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
        assertEquals(testCase.expectedErrorInfo, unpackErrorInfo(status))
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsGetMyOrganizationErrorTestCases")
    fun `toStatus correctly converts GetMyOrganizationError`(
        testCase: ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
        assertEquals(testCase.expectedErrorInfo, unpackErrorInfo(status))
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsGetOrganizationErrorTestCases")
    fun `toStatus correctly converts GetOrganizationError`(
        testCase: ToStatusCorrectlyConvertsGetOrganizationErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
        assertEquals(testCase.expectedErrorInfo, unpackErrorInfo(status))
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsGetSelfErrorTestCases")
    fun `toStatus correctly converts GetSelfError`(
        testCase: ToStatusCorrectlyConvertsGetSelfErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
        assertEquals(testCase.expectedErrorInfo, unpackErrorInfo(status))
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsListAppDraftSummariesErrorTestCases")
    fun `toStatus correctly converts ListAppDraftSummariesError`(
        testCase: ToStatusCorrectlyConvertsListAppDraftSummariesErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
        assertEquals(testCase.expectedErrorInfo, unpackErrorInfo(status))
    }

    @ParameterizedTest
    @MethodSource("toStatusCorrectlyConvertsServerErrorTestCases")
    fun `toStatus correctly converts ServerError`(
        testCase: ToStatusCorrectlyConvertsServerErrorTestCase,
    ) {
        val status = toStatus(testCase.error)

        assertEquals(testCase.expectedCode, Code.forNumber(status.code))
        assertTrue(status.message.isNotEmpty())
        assertEquals(testCase.expectedErrorInfo, unpackErrorInfo(status))
    }

    companion object {
        data class ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase(
            val error: CreateAppDraftError,
            val expectedCode: Code,
            val expectedErrorInfo: ErrorInfo,
        )

        data class ToStatusCorrectlyConvertsCreateAppDraftListingTextErrorTestCase(
            val error: CreateAppDraftListingTextError,
            val expectedCode: Code,
            val expectedErrorInfo: ErrorInfo,
        )

        data class ToStatusCorrectlyConvertsDeleteAppDraftErrorTestCase(
            val error: DeleteAppDraftError,
            val expectedCode: Code,
            val expectedErrorInfo: ErrorInfo,
        )

        data class ToStatusCorrectlyConvertsGetMyOrganizationErrorTestCase(
            val error: GetMyOrganizationError,
            val expectedCode: Code,
            val expectedErrorInfo: ErrorInfo,
        )

        data class ToStatusCorrectlyConvertsGetOrganizationErrorTestCase(
            val error: GetOrganizationError,
            val expectedCode: Code,
            val expectedErrorInfo: ErrorInfo,
        )

        data class ToStatusCorrectlyConvertsGetSelfErrorTestCase(
            val error: GetSelfError,
            val expectedCode: Code,
            val expectedErrorInfo: ErrorInfo,
        )

        data class ToStatusCorrectlyConvertsListAppDraftSummariesErrorTestCase(
            val error: ListAppDraftSummariesError,
            val expectedCode: Code,
            val expectedErrorInfo: ErrorInfo,
        )

        data class ToStatusCorrectlyConvertsServerErrorTestCase(
            val error: ServerError,
            val expectedCode: Code,
            val expectedErrorInfo: ErrorInfo,
        )

        @JvmStatic
        private fun toStatusCorrectlyConvertsCreateAppDraftErrorTestCases():
                List<ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase(
                    UnauthenticatedError(),
                    Code.UNAUTHENTICATED,
                    errorInfo(ErrorReason.ERROR_REASON_UNAUTHENTICATED),
                ),
                ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase(
                    InsufficientPermissionError(),
                    Code.PERMISSION_DENIED,
                    errorInfo(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION),
                ),
                ToStatusCorrectlyConvertsCreateAppDraftErrorTestCase(
                    ActiveAppDraftLimitExceededError(),
                    Code.RESOURCE_EXHAUSTED,
                    errorInfo(ErrorReason.ERROR_REASON_RESOURCE_LIMIT_EXCEEDED),
                ),
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsCreateAppDraftListingTextErrorTestCases():
                List<ToStatusCorrectlyConvertsCreateAppDraftListingTextErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsCreateAppDraftListingTextErrorTestCase(
                    UnauthenticatedError(),
                    Code.UNAUTHENTICATED,
                    errorInfo(ErrorReason.ERROR_REASON_UNAUTHENTICATED),
                ),
                ToStatusCorrectlyConvertsCreateAppDraftListingTextErrorTestCase(
                    InsufficientPermissionError(),
                    Code.PERMISSION_DENIED,
                    errorInfo(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION),
                ),
                ToStatusCorrectlyConvertsCreateAppDraftListingTextErrorTestCase(
                    AppDraftListingTextAlreadyExistsError(),
                    Code.ALREADY_EXISTS,
                    errorInfo(ErrorReason.ERROR_REASON_ALREADY_EXISTS),
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
                    errorInfo(ErrorReason.ERROR_REASON_UNAUTHENTICATED),
                ),
                ToStatusCorrectlyConvertsDeleteAppDraftErrorTestCase(
                    InsufficientPermissionError(),
                    Code.PERMISSION_DENIED,
                    errorInfo(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION),
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
                    errorInfo(ErrorReason.ERROR_REASON_UNAUTHENTICATED),
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
                    errorInfo(ErrorReason.ERROR_REASON_UNAUTHENTICATED),
                ),
                ToStatusCorrectlyConvertsGetOrganizationErrorTestCase(
                    InsufficientPermissionError(),
                    Code.PERMISSION_DENIED,
                    errorInfo(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION),
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
                    errorInfo(ErrorReason.ERROR_REASON_UNAUTHENTICATED),
                ),
            )
        }

        @JvmStatic
        private fun toStatusCorrectlyConvertsListAppDraftSummariesErrorTestCases():
                List<ToStatusCorrectlyConvertsListAppDraftSummariesErrorTestCase> {
            return listOf(
                ToStatusCorrectlyConvertsListAppDraftSummariesErrorTestCase(
                    UnauthenticatedError(),
                    Code.UNAUTHENTICATED,
                    errorInfo(ErrorReason.ERROR_REASON_UNAUTHENTICATED),
                ),
                ToStatusCorrectlyConvertsListAppDraftSummariesErrorTestCase(
                    InsufficientPermissionError(),
                    Code.PERMISSION_DENIED,
                    errorInfo(ErrorReason.ERROR_REASON_INSUFFICIENT_PERMISSION),
                ),
                ToStatusCorrectlyConvertsListAppDraftSummariesErrorTestCase(
                    InvalidPageTokenError(),
                    Code.INVALID_ARGUMENT,
                    errorInfo(ErrorReason.ERROR_REASON_INVALID_REQUEST),
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
                    errorInfo(ErrorReason.ERROR_REASON_INTERNAL),
                ),
                ToStatusCorrectlyConvertsServerErrorTestCase(
                    ServerError.ServiceUnavailableError(),
                    Code.UNAVAILABLE,
                    errorInfo(ErrorReason.ERROR_REASON_SERVICE_UNAVAILABLE),
                ),
            )
        }
    }
}

private fun errorInfo(reason: ErrorReason): ErrorInfo {
    return ErrorInfo.newBuilder()
        .setReason(reason.name)
        .setDomain(ERROR_DOMAIN)
        .build()
}

private fun unpackErrorInfo(status: Status): ErrorInfo {
    return status.detailsList.single().unpack(ErrorInfo::class.java)
}
