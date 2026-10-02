// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.domain.config

import app.accrescent.server.core.TcpPort
import java.net.InetAddress

/**
 * A server configuration.
 *
 * @property address the address for the server to listen on.
 * @property port the port for the server to listen on.
 * @property shutdownTimeout the maximum amount of time to wait for in-flight requests to complete
 * when shutting down.
 */
data class ServerConfig(
    val address: InetAddress,
    val port: TcpPort,
    val shutdownTimeout: ShutdownTimeout,
)
