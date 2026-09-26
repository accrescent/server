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
class UnusedEitherValueTest(private val env: KotlinEnvironmentContainer) {
    @Test
    fun `reports Either-returning call used as a statement`() {
        val code = """
            import arrow.core.Either

            fun returnsEither(): Either<String, Int> = Either.Right(1)

            fun test() {
                returnsEither()
            }
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports chained Either-returning call used as a statement`() {
        val code = """
            import arrow.core.Either

            fun returnsEither(): Either<String, Int> = Either.Right(1)

            fun test() {
                returnsEither().mapLeft { "error: ${'$'}it" }
            }
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `does not report when Either is assigned to a variable`() {
        val code = """
            import arrow.core.Either

            fun returnsEither(): Either<String, Int> = Either.Right(1)

            fun test() {
                val result = returnsEither()
            }
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report when Either is returned`() {
        val code = """
            import arrow.core.Either

            fun returnsEither(): Either<String, Int> = Either.Right(1)

            fun test(): Either<String, Int> = returnsEither()
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report when Either is passed as an argument`() {
        val code = """
            import arrow.core.Either

            fun returnsEither(): Either<String, Int> = Either.Right(1)
            fun consumesEither(e: Either<String, Int>) {}

            fun test() {
                consumesEither(returnsEither())
            }
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report Either as last expression in a try block`() {
        val code = """
            import arrow.core.Either
            import arrow.core.left
            import arrow.core.right

            fun test(): Either<String, Int> {
                return try {
                    42.right()
                } catch (e: Exception) {
                    "err".left()
                }
            }
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report Either as last expression in a lambda`() {
        val code = """
            import arrow.core.Either
            import arrow.core.right

            fun test(): Either<String, Int> {
                val result = run {
                    42.right()
                }
                return result
            }
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports Either reached through a typealias`() {
        val code = """
            import arrow.core.Either

            typealias MyResult<T> = Either<String, T>

            fun returnsEither(): MyResult<Int> = Either.Right(1)

            fun test() {
                returnsEither()
            }
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports Either reached through nested typealiases`() {
        val code = """
            import arrow.core.Either

            typealias Inner<E, T> = Either<E, T>
            typealias Outer<T> = Inner<String, T>

            fun returnsEither(): Outer<Int> = Either.Right(1)

            fun test() {
                returnsEither()
            }
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports Either discarded as the result of a Unit-returning lambda`() {
        val code = """
            import arrow.core.Either

            fun returnsEither(): Either<String, Int> = Either.Right(1)

            fun test() {
                val value = "x".also { returnsEither() }
            }
        """.trimIndent()

        val findings = UnusedEitherValue(Config.empty).lintWithContext(env, code)

        assertEquals(1, findings.size)
    }
}
