package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.asciiIntOrNull
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome

/**
 * One source-classified computation action retained for later application. The outcome keeps the three
 * application branches distinct: VALUE and CLEARED create a missing target, while ERRORED clears only an
 * already-present destination target.
 */
internal data class ComputationApplication(
    val path: String,
    val reps: List<Int>,
    val outcome: ComputeOutcome,
    val value: String?,
)

/**
 * The V2 object identity of a converted Number source. Java BigDecimal equality includes both coefficient and
 * scale, including a negative scale that its plain serializer text cannot reveal (`1e1` writes as `10`).
 */
internal data class DecimalObjectIdentity(
    val coefficient: String,
    val scale: Int,
) {
    companion object {
        private val DECIMAL = Regex("([-+]?)([0-9]*)(?:\\.([0-9]*))?(?:[eE]([-+]?[0-9]+))?")

        fun parse(text: String): DecimalObjectIdentity? {
            val match = DECIMAL.matchEntire(text) ?: return null
            val (sign, integerPart, fractionPart, exponentPart) = match.destructured
            if (integerPart.isEmpty() && fractionPart.isEmpty()) return null
            val exponent = if (exponentPart.isEmpty()) 0 else exponentPart.asciiIntOrNull() ?: return null
            val scale = fractionPart.length.toLong() - exponent
            if (scale !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
            val magnitude = (integerPart + fractionPart).trimStart('0').ifEmpty { "0" }
            val coefficient = if (sign == "-" && magnitude != "0") "-$magnitude" else magnitude
            return DecimalObjectIdentity(coefficient, scale.toInt())
        }
    }
}
