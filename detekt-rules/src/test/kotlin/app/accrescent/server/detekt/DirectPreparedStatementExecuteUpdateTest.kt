// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.detekt

import dev.detekt.api.Config
import dev.detekt.test.junit.KotlinCoreEnvironmentTest
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@KotlinCoreEnvironmentTest
class DirectPreparedStatementExecuteUpdateTest(private val env: KotlinEnvironmentContainer) {
    @Test
    fun `reports executeUpdate called on a PreparedStatement`() {
        val code = """
            import java.sql.PreparedStatement

            fun update(ps: PreparedStatement): Int = ps.executeUpdate()
        """.trimIndent()

        val findings = DirectPreparedStatementExecuteUpdate(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }
}
