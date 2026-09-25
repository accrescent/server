// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.adapters.driving.api.console.grpc

import build.buf.gen.accrescent.console.v1.ReviewServiceGrpcKt
import kotlin.coroutines.CoroutineContext

class ReviewServiceImpl(
    context: CoroutineContext,
) : ReviewServiceGrpcKt.ReviewServiceCoroutineImplBase(context)
