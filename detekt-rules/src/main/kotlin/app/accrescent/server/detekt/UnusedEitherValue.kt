// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports `Either` values returned by a call expression whose result is silently discarded.
 *
 * Arrow's `Either` is a typed error channel — discarding it at the call site means the error case
 * goes unhandled. This rule enforces the same discipline as Rust's `must_use` on `Result`:
 * every `Either`-returning call must have its value assigned, returned, or passed to another
 * expression.
 */
class UnusedEitherValue(config: Config) :
    Rule(
        config,
        "Reports `Either` values that are silently discarded at the call site, leaving the " +
            "error case unhandled.",
    ),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        analyze(expression) {
            val type = expression.expressionType as? KaClassType ?: return
            if (type.classId != EITHER_CLASS_ID || expression.isUsedAsExpression) return
        }

        report(
            Finding(
                Entity.from(expression),
                "This `Either` value is silently discarded. Handle both the left and right " +
                    "cases, or propagate the value to the caller.",
            )
        )
    }

    companion object {
        private val EITHER_CLASS_ID = ClassId.topLevel(FqName("arrow.core.Either"))
    }
}
