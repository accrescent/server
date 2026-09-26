// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

/**
 * An Android binary [XML attribute](https://www.w3.org/TR/2008/REC-xml-20081126/#dt-attr).
 *
 * Corresponds to the
 * [`ResXMLTree_attribute`](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/androidfw/include/androidfw/ResourceTypes.h#743)
 * struct in AOSP.
 *
 * @property id the attribute's ID.
 * @property value the attribute's typed value.
 */
data class XmlAttribute(
    val id: XmlAttributeId,
    val value: ResourceValue,
)
