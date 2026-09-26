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
class TruncatingIntegerConversionTest(private val env: KotlinEnvironmentContainer) {
    @Test
    fun `does not report toULong called on a Long`() {
        val code = """
            fun convert(n: Long): ULong = n.toULong()
        """.trimIndent()

        val findings = TruncatingIntegerConversion(Config.empty).lintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report toUInt called on an Int`() {
        val code = """
            fun convert(n: Int): UInt = n.toUInt()
        """.trimIndent()

        val findings = TruncatingIntegerConversion(Config.empty).lintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports toByte called on an Int`() {
        val code = """
            fun convert(n: Int): Byte = n.toByte()
        """.trimIndent()

        val findings = TruncatingIntegerConversion(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports toUInt called on a Long`() {
        val code = """
            fun convert(n: Long): UInt = n.toUInt()
        """.trimIndent()

        val findings = TruncatingIntegerConversion(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `does not report toLong called on a UInt`() {
        val code = """
            fun convert(n: UInt): Long = n.toLong()
        """.trimIndent()

        val findings = TruncatingIntegerConversion(Config.empty).lintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report toLong called on an Int`() {
        val code = """
            fun convert(n: Int): Long = n.toLong()
        """.trimIndent()

        val findings = TruncatingIntegerConversion(Config.empty).lintWithContext(env, code)

        assertEquals(0, findings.size)
    }
}
