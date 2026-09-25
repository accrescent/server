// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.ports.driving.api.console

import app.accrescent.server.parcelo.domain.uri.HttpUri

data class DownloadAppDraftResponse(val apkSetUri: HttpUri)
