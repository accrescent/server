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
class JavaBase64UsageTest(private val env: KotlinEnvironmentContainer) {
    @Test
    fun `reports import of java util Base64`() {
        val code = "import java.util.Base64"

        val findings = JavaBase64Usage(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports import of java util Base64 Encoder`() {
        val code = "import java.util.Base64.Encoder"

        val findings = JavaBase64Usage(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports getEncoder called on Base64`() {
        val code = "val e = java.util.Base64.getEncoder()"

        val findings = JavaBase64Usage(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports getDecoder called on Base64`() {
        val code = "val d = java.util.Base64.getDecoder()"

        val findings = JavaBase64Usage(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports encodeToString called on Base64 Encoder`() {
        val code = """
            fun encode(bytes: ByteArray): String {
                val encoder = java.util.Base64.getEncoder()
                return encoder.encodeToString(bytes)
            }
        """.trimIndent()

        val findings = JavaBase64Usage(Config.empty).lintWithContext(env, code)

        // getEncoder (1) + encodeToString (1)
        assertEquals(2, findings.size)
    }

    @Test
    fun `reports decode called on Base64 Decoder`() {
        val code = """
            fun decode(str: String): ByteArray {
                val decoder = java.util.Base64.getDecoder()
                return decoder.decode(str)
            }
        """.trimIndent()

        val findings = JavaBase64Usage(Config.empty).lintWithContext(env, code)

        // getDecoder (1) + decode (1)
        assertEquals(2, findings.size)
    }

    @Test
    fun `reports type reference to Base64 Encoder`() {
        val code = """
            fun encode(encoder: java.util.Base64.Encoder, bytes: ByteArray): String =
                encoder.encodeToString(bytes)
        """.trimIndent()

        val findings = JavaBase64Usage(Config.empty).lintWithContext(env, code)

        // type ref (1) + encodeToString (1)
        assertEquals(2, findings.size)
    }
}
