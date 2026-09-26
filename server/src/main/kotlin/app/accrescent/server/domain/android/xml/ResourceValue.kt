// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

/**
 * A typed value in an Android binary resource.
 *
 * Corresponds to the
 * [`Res_value`](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/androidfw/include/androidfw/ResourceTypes.h#291)
 * struct in AOSP.
 */
sealed class ResourceValue {
    /**
     * A raw decimal integer value.
     *
     * Corresponds to [`TYPE_INT_DEC`](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/androidfw/include/androidfw/ResourceTypes.h#331)
     * in AOSP.
     */
    data class IntDec(val value: Int) : ResourceValue()

    /**
     * A string resolved via the containing resource table's global value string pool.
     *
     * Corresponds to [`TYPE_STRING`](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/androidfw/include/androidfw/ResourceTypes.h#311)
     * in AOSP.
     */
    data class String(val value: kotlin.String) : ResourceValue()

    /**
     * A boolean.
     *
     * Corresponds to [`TYPE_INT_BOOLEAN`](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/androidfw/include/androidfw/ResourceTypes.h#335)
     * in AOSP.
     */
    data class Bool(val value: Boolean) : ResourceValue()

    /**
     * A fallback type for resource values with an unrecognized type.
     *
     * @property value the raw value of this resource value.
     */
    data class Unsupported(val value: UInt) : ResourceValue()
}
