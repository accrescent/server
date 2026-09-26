// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.build

import org.gradle.api.attributes.Attribute

interface ApkSetNameAttr {
    companion object {
        val ATTRIBUTE =
            Attribute.of("app.accrescent.server.build.apk-set-name", String::class.java)
    }
}
