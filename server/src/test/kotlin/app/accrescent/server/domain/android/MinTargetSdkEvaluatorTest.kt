// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.unwrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MinTargetSdkEvaluatorTest {
    @Test
    fun `getMinTargetSdk returns 36 on 2026-08-31`() {
        val result = MinTargetSdkEvaluator.getMinTargetSdk(midnightOf(2026, 8, 31))

        assertEquals(sdkVersion(36), result)
    }

    @Test
    fun `getMinTargetSdk returns 36 after 2026-08-31`() {
        val result = MinTargetSdkEvaluator.getMinTargetSdk(midnightOf(2026, 9, 1))

        assertEquals(sdkVersion(36), result)
    }

    @Test
    fun `getMinTargetSdk returns 35 between 2025-08-31 and 2026-08-31`() {
        val result = MinTargetSdkEvaluator.getMinTargetSdk(midnightOf(2026, 1, 1))

        assertEquals(sdkVersion(35), result)
    }

    @Test
    fun `getMinTargetSdk returns 29 before 2025-08-31`() {
        val result = MinTargetSdkEvaluator.getMinTargetSdk(midnightOf(2025, 1, 1))

        assertEquals(sdkVersion(29), result)
    }

    companion object {
        private fun midnightOf(year: Int, month: Int, dayOfMonth: Int): OffsetDateTime {
            return OffsetDateTime.of(year, month, dayOfMonth, 0, 0, 0, 0, ZoneOffset.UTC)
        }

        private fun sdkVersion(value: Int): SdkVersion {
            return SdkVersion.fromInt(value).unwrap()
        }
    }
}
