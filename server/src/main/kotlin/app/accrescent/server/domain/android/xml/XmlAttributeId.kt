// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

import arrow.core.Option

/**
 * An Android binary XML attribute ID for a given element.
 *
 * Valid attributes in Android binary XML will always have a local name and may have a namespace
 * name (URI) and/or resource ID. For our purposes, the combined attribute local name and namespace
 * name must be unique for a given XML element. Likewise, a resource ID, if present, must be unique
 * for a given XML element. The Android platform may accept binary XML without these constraints;
 * however, since violating these constraints is not generally possible with official developer
 * tooling and allowing violations makes parser differential vulnerabilities very difficult to
 * avoid, we enforce them by finding element attributes via instances of this class.
 *
 * An instance of this class does NOT necessarily uniquely identify an element attribute. For
 * example, two different instances with the same name but different resource ID refer to the same
 * attribute. However, at most one of these will be considered valid because at most one can have
 * all of its fields be correct.
 *
 * @property name the name of the attribute to identify.
 * @property resourceId the resource ID of the attribute to identify, if applicable.
 */
data class XmlAttributeId(val name: XmlExpandedName, val resourceId: Option<ResourceId>)
