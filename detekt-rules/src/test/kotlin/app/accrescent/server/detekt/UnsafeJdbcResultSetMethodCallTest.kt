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
class UnsafeJdbcResultSetMethodCallTest(private val env: KotlinEnvironmentContainer) {
    @Test
    fun `reports getString called on a ResultSet`() {
        val code = """
            import java.sql.ResultSet

            fun read(rs: ResultSet): String = rs.getString("column")
        """.trimIndent()

        val findings = UnsafeJdbcResultSetMethodCall(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports getBoolean called on a ResultSet`() {
        val code = """
            import java.sql.ResultSet

            fun read(rs: ResultSet): Boolean = rs.getBoolean(1)
        """.trimIndent()

        val findings = UnsafeJdbcResultSetMethodCall(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports getObject called on a ResultSet`() {
        val code = """
            import java.sql.ResultSet
            import java.time.OffsetDateTime

            fun read(rs: ResultSet): OffsetDateTime =
                rs.getObject("column", OffsetDateTime::class.java)
        """.trimIndent()

        val findings = UnsafeJdbcResultSetMethodCall(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }
}
