// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import app.accrescent.server.core.unwrap
import arrow.core.firstOrNone
import arrow.core.getOrElse
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Evaluates the minimum target SDK for new apps and app updates.
 */
object MinTargetSdkEvaluator {
    /**
     * Retrieves the current minimum target SDK for new apps and app updates.
     *
     * @param currentTime the current time.
     * @return the minimum target SDK for new apps and updates based on the current time.
     */
    fun getMinTargetSdk(currentTime: OffsetDateTime): SdkVersion {
        return minTargetSdks
            .firstOrNone { (threshold, _) -> !currentTime.isBefore(threshold) }
            .map { it.second }
            .getOrElse { DEFAULT_MIN_TARGET_SDK }
    }

    // Sourced from https://developer.android.com/google/play/requirements/target-sdk
    private val minTargetSdks = listOf(
        OffsetDateTime.of(2026, 8, 31, 0, 0, 0, 0, ZoneOffset.UTC) to SdkVersion.fromInt(36).unwrap(),
        OffsetDateTime.of(2025, 8, 31, 0, 0, 0, 0, ZoneOffset.UTC) to SdkVersion.fromInt(35).unwrap(),
    )

    // The minimum supported API level of the Accrescent app as of version 0.28.1 according to
    // https://github.com/accrescent/accrescent/blob/0.28.1/app/build.gradle.kts#L103
    private val DEFAULT_MIN_TARGET_SDK = SdkVersion.fromInt(29).unwrap()
}
