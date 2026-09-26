// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Reports any usage of Java's `java.util.Base64` class, including imports, type references, and
 * method calls on `Base64`, `Base64.Encoder`, and `Base64.Decoder`.
 *
 * Kotlin's `kotlin.io.encoding.Base64` provides idiomatic Base64 encoding and decoding and should
 * be used instead.
 */
class JavaBase64Usage(config: Config) :
    Rule(
        config,
        "Forbids usage of `java.util.Base64`. Use `kotlin.io.encoding.Base64` instead.",
    ),
    RequiresAnalysisApi {

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)

        val fqName = importDirective.importedFqName?.asString() ?: return
        reportIfBase64(fqName, importDirective)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        analyze(expression) {
            val classId = resolvedFunctionCall(expression)?.symbol?.callableId?.classId ?: return
            reportIfBase64(classId.asFqNameString(), expression)
        }
    }

    override fun visitTypeReference(typeReference: KtTypeReference) {
        super.visitTypeReference(typeReference)

        analyze(typeReference) {
            val type = typeReference.type as? KaClassType ?: return
            reportIfBase64(type.classId.asFqNameString(), typeReference)
        }
    }

    private fun reportIfBase64(fqName: String, source: PsiElement) {
        if (fqName == JAVA_BASE64_FQN || fqName.startsWith("$JAVA_BASE64_FQN.")) {
            report(Finding(Entity.from(source), MESSAGE))
        }
    }

    companion object {
        private const val JAVA_BASE64_FQN = "java.util.Base64"
        private const val MESSAGE =
            "Use `kotlin.io.encoding.Base64` instead of `java.util.Base64`."
    }
}
