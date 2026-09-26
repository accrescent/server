// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * An Android application
 * [version name](https://developer.android.com/studio/publish/versioning#versioningsettings).
 *
 * A version name is
 * [limited to 1024 characters](https://developer.android.com/guide/topics/manifest/manifest-intro#limits),
 * which this class interprets to mean Unicode code points in accordance with
 * [AIP 210](https://google.aip.dev/210#character-definition).
 *
 * @property value the string representation of this version name.
 */
@JvmInline
value class VersionName private constructor(val value: String) {
    companion object {
        private const val MAX_LENGTH = 1024

        /**
         * Creates a version name from a string.
         *
         * @param value the value to create a version name from.
         * @return a version name with the value of [value], or [None] if [value] is not a valid
         * version name.
         */
        fun fromString(value: String): Option<VersionName> {
            return if (value.codePointCount(0, value.length) <= MAX_LENGTH) {
                Some(VersionName(value))
            } else {
                None
            }
        }
    }
}
