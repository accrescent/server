// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * An Android
 * [SDK version](https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels),
 * also known as an API level.
 *
 * @property value the integer representation of this SDK version.
 */
@JvmInline
value class SdkVersion private constructor(val value: Int) : Comparable<SdkVersion> {
    companion object {
        val MINIMUM = SdkVersion(1)

        /**
         * Creates an SDK version from an integer.
         *
         * @param value the value to create an SDK version from.
         * @return an SDK version with the value of [value], or [None] if [value] is not in the
         * range of valid SDK version values.
         */
        fun fromInt(value: Int): Option<SdkVersion> {
            return if (value > 0) {
                Some(SdkVersion(value))
            } else {
                None
            }
        }
    }

    override fun compareTo(other: SdkVersion): Int {
        return value.compareTo(other.value)
    }
}
