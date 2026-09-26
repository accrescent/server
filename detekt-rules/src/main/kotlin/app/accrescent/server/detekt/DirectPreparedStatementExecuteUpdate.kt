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
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports direct calls to `java.sql.PreparedStatement.executeUpdate()`.
 *
 * Calling `executeUpdate()` directly returns a raw row count that callers must interpret manually,
 * leading to repetitive boilerplate and easy-to-miss error paths (e.g., zero rows updated,
 * unexpectedly more than one row updated). Use `executeSingleUpdate()` instead, which encodes
 * the expected single-row contract in the return type and maps the result to a `DataStoreResult`.
 *
 * The `executeSingleUpdate()` implementation itself is the only place this method may legitimately
 * be called; suppress the rule there with `@Suppress("DirectPreparedStatementExecuteUpdate")`.
 */
class DirectPreparedStatementExecuteUpdate(config: Config) :
    Rule(
        config,
        "Forbids calling `java.sql.PreparedStatement.executeUpdate()` directly. " +
            "Use `executeSingleUpdate()` instead.",
    ),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        if (expression.calleeExpression?.text != "executeUpdate") return

        analyze(expression) {
            val callableId = resolvedFunctionCall(expression)?.symbol?.callableId ?: return
            if (callableId.classId != PREPARED_STATEMENT_CLASS_ID ||
                callableId.callableName.asString() != "executeUpdate"
            ) {
                return
            }
            report(
                Finding(
                    Entity.from(expression),
                    "Calling `PreparedStatement.executeUpdate()` directly is forbidden. " +
                        "Use `executeSingleUpdate()` instead.",
                )
            )
        }
    }

    companion object {
        private val PREPARED_STATEMENT_CLASS_ID = ClassId.topLevel(FqName("java.sql.PreparedStatement"))
    }
}
