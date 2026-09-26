// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.android

import com.android.bundle.Commands

fun Commands.BuildApksResult.apkPaths(): Set<String> {
    return variantList
        .flatMap { it.apkSetList }
        .flatMap { it.apkDescriptionList }
        .map { it.path }
        // Paths can be repeated across variants, so deduplicate them here
        .toSet()
}
