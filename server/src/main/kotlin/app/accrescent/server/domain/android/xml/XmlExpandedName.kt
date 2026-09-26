// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android.xml

import app.accrescent.server.domain.uri.Uri
import arrow.core.Option

/**
 * An Android binary
 * [XML expanded name](https://www.w3.org/TR/2009/REC-xml-names-20091208/#dt-expname).
 *
 * @property namespaceName the
 * [namespace name](https://www.w3.org/TR/2009/REC-xml-names-20091208/#dt-NSName) component of this
 * expanded name.
 * @property localName the
 * [local name](https://www.w3.org/TR/2009/REC-xml-names-20091208/#dt-localname) component of this
 * expanded name.
 */
data class XmlExpandedName(
    val namespaceName: Option<Uri>,
    val localName: AsciiNcName,
)
