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
 * Reports calls to `java.sql.ResultSet` accessor methods that expose Kotlin platform types.
 *
 * Methods such as [java.sql.ResultSet.getString] and [java.sql.ResultSet.getInt] are declared in
 * Java without nullability information, so Kotlin sees them as platform types. The compiler will
 * happily assign their result to a non-null type and only throw a `NullPointerException` at runtime
 * if the column was actually `NULL`. The null-safe wrappers in `ResultSetExt.kt` (e.g.
 * `getSafeString`) return honest nullable types instead, so all production code must use those.
 *
 * The wrapper implementations themselves are the only place these raw methods may legitimately be
 * called; suppress the rule there with `@Suppress("UnsafeJdbcResultSetMethodCall")`.
 */
class UnsafeJdbcResultSetMethodCall(config: Config) :
    Rule(
        config,
        "Forbids `java.sql.ResultSet` accessor methods that return Kotlin platform types. " +
                "Use the null-safe wrappers in ResultSetExt.kt instead.",
    ),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val methodName = expression.calleeExpression?.text ?: return
        if (methodName !in FORBIDDEN_METHODS) return

        val classId = analyze(expression) {
            resolvedFunctionCall(expression)?.symbol?.callableId?.classId
        }
        if (classId != RESULT_SET_CLASS_ID) return

        report(
            Finding(
                Entity.from(expression),
                "`ResultSet.$methodName` returns a Kotlin platform type that can hide a SQL NULL. " +
                        "Use the corresponding null-safe wrapper from ResultSetExt.kt instead.",
            )
        )
    }

    companion object {
        private val RESULT_SET_CLASS_ID = ClassId.topLevel(FqName("java.sql.ResultSet"))

        // The full set of column-accessor getters declared on java.sql.ResultSet. Each returns a
        // reference type or an unboxed primitive whose Kotlin platform type can mask a SQL NULL.
        private val FORBIDDEN_METHODS = listOf(
            "getArray",
            "getAsciiStream",
            "getBigDecimal",
            "getBinaryStream",
            "getBlob",
            "getBoolean",
            "getByte",
            "getBytes",
            "getCharacterStream",
            "getClob",
            "getDate",
            "getDouble",
            "getFloat",
            "getInt",
            "getLong",
            "getNCharacterStream",
            "getNClob",
            "getNString",
            "getObject",
            "getRef",
            "getRowId",
            "getSQLXML",
            "getShort",
            "getString",
            "getTime",
            "getTimestamp",
            "getUnicodeStream",
            "getURL",
        )
    }
}
