// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.ports.driving.api.console

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import java.time.OffsetDateTime

sealed class AppDraft {
    abstract val id: String
    abstract val createTime: OffsetDateTime

    data class Unsubmitted(
        override val id: String,
        override val createTime: OffsetDateTime,
        val defaultAppDraftListingId: Option<String>,
        val appPackage: Option<AppPackage>,
    ) : AppDraft()

    data class Submitted(
        override val id: String,
        override val createTime: OffsetDateTime,
        val defaultAppDraftListingId: String,
        val appPackage: AppPackage,
        val submitTime: OffsetDateTime,
    ) : AppDraft()

    data class Published(
        override val id: String,
        override val createTime: OffsetDateTime,
        val defaultAppDraftListingId: String,
        val appPackage: AppPackage,
        val submitTime: OffsetDateTime,
        val publishTime: OffsetDateTime,
    ) : AppDraft()

    val optionalDefaultAppDraftListingId: Option<String>
        get() = when (this) {
            is Unsubmitted -> this.defaultAppDraftListingId
            is Submitted -> Some(this.defaultAppDraftListingId)
            is Published -> Some(this.defaultAppDraftListingId)
        }

    val optionalAppPackage: Option<AppPackage>
        get() = when (this) {
            is Unsubmitted -> this.appPackage
            is Submitted -> Some(this.appPackage)
            is Published -> Some(this.appPackage)
        }

    val optionalSubmitTime: Option<OffsetDateTime>
        get() = when (this) {
            is Unsubmitted -> None
            is Submitted -> Some(this.submitTime)
            is Published -> Some(this.submitTime)
        }

    val optionalPublishTime: Option<OffsetDateTime>
        get() = when (this) {
            is Unsubmitted -> None
            is Submitted -> None
            is Published -> Some(this.publishTime)
        }
}
