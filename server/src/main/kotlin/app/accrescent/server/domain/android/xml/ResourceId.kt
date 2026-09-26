// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

/**
 * An Android
 * [resource ID](https://developer.android.com/guide/topics/resources/providing-resources).
 *
 * Corresponds to
 * [`ResourceId`](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/androidfw/include/androidfw/ResourceTypes.h#1996)
 * in AOSP. The value can be retrieved by indexing the resource file's
 * [resource map](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/androidfw/include/androidfw/ResourceTypes.h#254)
 * with the string pool index of, e.g., an attribute's name.
 *
 * @property value the raw value of this resource ID.
 */
@JvmInline
value class ResourceId(val value: UInt)
