// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.parcelo.domain.ports.driving.api.console

import arrow.core.None
import arrow.core.Option

/**
 * The context for an API call.
 *
 * @property sessionId the ID of the session the API call is made with, or [None] if the call is
 * made without one.
 */
data class CallContext(val sessionId: Option<String>)
