// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import build.buf.gen.accrescent.console.v1.OrganizationServiceGrpcKt
import kotlin.coroutines.CoroutineContext

class OrganizationServiceImpl(
    context: CoroutineContext,
) : OrganizationServiceGrpcKt.OrganizationServiceCoroutineImplBase(context)
