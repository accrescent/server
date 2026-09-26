// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.build

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

interface ApkAttr : Named {
    companion object {
        val ATTRIBUTE = Attribute.of(ApkAttr::class.java)
        const val PRESENT = "present"
    }
}
