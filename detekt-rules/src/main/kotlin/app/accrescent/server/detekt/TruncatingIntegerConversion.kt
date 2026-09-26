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
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports calls to Kotlin's built-in integer conversion methods that narrow to a smaller bit
 * width.
 *
 * Methods such as [Int.toByte] and [UInt.toShort] are infallible, but silently truncate the value
 * when it doesn't fit in the target type's bit width, potentially causing unexpected behavior.
 */
class TruncatingIntegerConversion(config: Config) :
    Rule(
        config,
        "Forbids Kotlin's built-in integer conversion methods that silently truncate values. " +
                "Use a checked conversion that returns a typed error instead.",
    ),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val calleeName = expression.calleeExpression?.text ?: return
        if (calleeName !in CONVERSION_NAMES) return

        val methodName = analyze(expression) {
            val call = resolvedFunctionCall(expression) ?: return
            val methodName = call.symbol.callableId?.callableName?.asString() ?: return
            val targetWidth = CONVERSION_TARGET_BITS[methodName] ?: return
            val receiverType = (call.dispatchReceiver ?: call.extensionReceiver)
                ?.type as? KaClassType ?: return
            val fqn = receiverType.classId.asFqNameString()
            val sourceWidth = INTEGER_TYPE_BITS[fqn] ?: return
            if (targetWidth >= sourceWidth) return
            methodName
        }

        report(
            Finding(
                Entity.from(expression),
                "`$methodName` silently truncates the value when it doesn't fit in the target type",
            )
        )
    }

    companion object {
        // Each Kotlin integer type's bit width, alongside its FQN and the name of the method that
        // converts to it, e.g. Int.SIZE_BITS, "kotlin.Int", "toInt".
        private val INTEGER_TYPES: List<Triple<String, String, Int>> = listOf(
            Triple("kotlin.Byte", "toByte", Byte.SIZE_BITS),
            Triple("kotlin.UByte", "toUByte", UByte.SIZE_BITS),
            Triple("kotlin.Short", "toShort", Short.SIZE_BITS),
            Triple("kotlin.UShort", "toUShort", UShort.SIZE_BITS),
            Triple("kotlin.Int", "toInt", Int.SIZE_BITS),
            Triple("kotlin.UInt", "toUInt", UInt.SIZE_BITS),
            Triple("kotlin.Long", "toLong", Long.SIZE_BITS),
            Triple("kotlin.ULong", "toULong", ULong.SIZE_BITS),
        )

        // The bit width of each Kotlin integer type, keyed by FQN
        private val INTEGER_TYPE_BITS: Map<String, Int> =
            INTEGER_TYPES.associate { (fqName, _, bits) -> fqName to bits }

        // The bit width of the type each conversion method converts to, keyed by method name.
        private val CONVERSION_TARGET_BITS: Map<String, Int> =
            INTEGER_TYPES.associate { (_, methodName, bits) -> methodName to bits }

        private val CONVERSION_NAMES: Set<String> = CONVERSION_TARGET_BITS.keys
    }
}
