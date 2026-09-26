// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driven.file

import app.accrescent.server.domain.ports.driven.file.TempFileConformanceTest

class LocalTempFileConformanceTest : TempFileConformanceTest() {
    override val factory = LocalTempFile
}
