// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.ports.driving.api.console

import arrow.core.Either

interface AppApi {
    fun getApp(context: CallContext, request: GetAppRequest): Either<GetAppError, GetAppResponse>
    fun updateApp(context: CallContext, request: UpdateAppRequest): Either<UpdateAppError, Unit>
}
