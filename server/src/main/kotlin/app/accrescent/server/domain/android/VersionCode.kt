// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * An Android application
 * [long version code](https://developer.android.com/reference/android/content/pm/PackageInfo#getLongVersionCode())
 * which is allowed to be published on Accrescent.
 *
 * The range of accepted values is `[1, 2100000000]`. For rationale, read below.
 *
 * Because [versionCodeMajor](https://developer.android.com/reference/android/R.attr#versionCodeMajor)
 * wasn't introduced until API 28, it's completely ignored on API 27 and below. This behavior
 * results in inconsistent total ordering for app versions across Android versions. For example,
 * consider the following app:
 *
 * - Version A: versionCode = 2, versionCodeMajor = 0 -> longVersionCode = 2
 * - Version B: versionCode = 1, versionCodeMajor = 1 -> longVersionCode = 4294967297
 *
 * Android 8.1 compares only versionCode, resulting in A > B. However, Android 9 calculates and
 * compares longVersionCode, resulting in B > A. Thus, there's no simple way to compare app versions
 * in a consistent way across both API 27- and API 28+ when versionCodeMajor != 0.
 *
 * Further,
 * [Google Play limits versionCode values](https://developer.android.com/studio/publish/versioning#versioningsettings)
 * to the range `[1, 2100000000]`. If we accepted versionCodeMajor values != 0 on Accrescent, an app
 * developer may publish an app with a non-zero versionCodeMajor without understanding the full
 * implications of doing so, making their app unpublishable to Play as-is. They could develop a
 * different versioning strategy per-store to work around this limitation, but it's much simpler to
 * limit app versions to the same range as Play for now for maximum compatibility with minimal
 * developer friction.
 *
 * @property value the integer representation of this version code.
 */
@JvmInline
value class VersionCode private constructor(val value: Int) {
    companion object {
        /**
         * Creates a version code from an integer.
         *
         * @param value the value to create a version code from.
         * @return a version code with the value of [value], or [None] if [value] is not in the set
         * of valid version code values.
         */
        fun fromInt(value: Int): Option<VersionCode> {
            return if (value in 1..2100000000) {
                Some(VersionCode(value))
            } else {
                None
            }
        }
    }
}
