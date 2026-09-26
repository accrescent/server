// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driven.datastore

import arrow.core.Option
import java.time.OffsetDateTime

sealed class AppDraftApiView {
    abstract val id: String
    abstract val createTime: OffsetDateTime

    data class Unsubmitted(
        override val id: String,
        override val createTime: OffsetDateTime,
        val defaultAppDraftListingId: Option<String>,
        val appPackage: Option<AppPackageApiView>,
    ) : AppDraftApiView()

    data class Submitted(
        override val id: String,
        override val createTime: OffsetDateTime,
        val defaultAppDraftListingId: String,
        val appPackage: AppPackageApiView,
        val submitTime: OffsetDateTime,
    ) : AppDraftApiView()
}
