// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

/**
 * An Android binary [XML element](https://www.w3.org/TR/2008/REC-xml-20081126/#dt-element).
 *
 * @property name the element's
 * [expanded name](https://www.w3.org/TR/2009/REC-xml-names-20091208/#dt-expname).
 * @property attributes the element's
 * [attributes](https://www.w3.org/TR/2008/REC-xml-20081126/#dt-attr).
 * @property children the element's
 * [child elements](https://www.w3.org/TR/2008/REC-xml-20081126/#dt-parentchild).
 */
data class XmlElement(
    val name: XmlExpandedName,
    val attributes: XmlAttributes,
    val children: List<XmlElement>,
)
