// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import build.buf.gen.accrescent.console.v1.UserServiceGrpcKt
import kotlin.coroutines.CoroutineContext

class UserServiceImpl(
    context: CoroutineContext,
) : UserServiceGrpcKt.UserServiceCoroutineImplBase(context)
