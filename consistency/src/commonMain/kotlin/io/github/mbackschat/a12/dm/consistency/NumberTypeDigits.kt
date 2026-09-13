package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A declared number type's fractional-digit pair and its value range.
 *
 * Measured as one grid ([KERNEL-FINDINGS KF320], `rulekit.validate.laws.NumberTypeDigitLawsTest`), and the
 * digit law is not the one its name suggests. **An ABSENT member reads as 0 and a NEGATIVE maximum takes the
 * pair out of the check** — so `minFractionalDigits: 2` with no maximum IS refused, while the same minimum
 * beside an explicit `-1` is admitted. A plain `min > max` over the declared members disagrees with A12 on
 * both of those, in opposite directions.
 *
 * The value range beside it is the ordinary law: both bounds declared, equal admitted, inverted refused.
 *
 * **The scale cap is a different tier and is reported as one.** A `maxFractionalDigits` above 14 leaves A12
 * able to READ the model and unable to BUILD it — no `MVK_*` code exists for it — so a consumer handed
 * silence would read an unusable model as clean. That is what [CK_MODEL_NOT_BUILDABLE] is for.
 */
fun PreparedModel.numberTypeDigitFindings(): List<Finding> =
    elements.filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val body = field.numberTypeBody() ?: return@flatMap emptyList()
        digitPairFindings(field, body) + valueRangeFindings(field, body)
    }

/** A12's cap on a declared scale, measured: 14 builds, 15 does not. */
private const val MAX_SCALE = 14

private fun digitPairFindings(field: DeclaredElement, body: JsonObject): List<Finding> {
    val min = body.int("minFractionalDigits") ?: 0
    val max = body.int("maxFractionalDigits") ?: 0
    val findings = mutableListOf<Finding>()
    if (max >= 0 && min > max) {
        findings += finding(
            field,
            "MVK_FRACT_DIGITS_INVALID",
            "this field declares $min minimum fractional digits above its maximum of $max, which A12 " +
                "refuses. A12 reads an ABSENT `maxFractionalDigits` as 0, so a minimum with no maximum is " +
                "this same inversion",
        )
    }
    for (member in listOf("minFractionalDigits", "maxFractionalDigits")) {
        val declared = body.int(member) ?: continue
        if (declared > MAX_SCALE) {
            findings += Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                CK_MODEL_NOT_BUILDABLE,
                "`$member` is $declared and A12 caps a declared scale at $MAX_SCALE, so A12 reads the " +
                    "model and then fails to build it — no consistency verdict about it exists, and A12 " +
                    "has no code of its own for this",
                field.path,
            )
        }
    }
    return findings
}

private fun valueRangeFindings(field: DeclaredElement, body: JsonObject): List<Finding> {
    val min = body.number("minValue") ?: return emptyList()
    val max = body.number("maxValue") ?: return emptyList()
    if (min <= max) {
        return emptyList()
    }
    return listOf(
        finding(
            field,
            "MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE",
            "this field declares a `minValue` of $min above its `maxValue` of $max, which A12 refuses; " +
                "equal bounds are admitted",
        ),
    )
}

/** The `NumberType` body this field declares, or null when it declares another type or none. */
private fun DeclaredElement.numberTypeBody(): JsonObject? {
    val declared = body?.get("fieldType") as? JsonObject ?: return null
    if (declared.string("type") != "NumberType") {
        return null
    }
    return declared["NumberType"] as? JsonObject
}

private fun JsonObject.int(member: String): Int? = (this[member] as? JsonPrimitive)?.intOrNull

/**
 * A declared decimal bound, as a comparable magnitude.
 *
 * Read as a double deliberately: the only question asked of it is an ORDERING, and a portable checker has no
 * arbitrary-precision decimal on every host. The precision this loses is far beyond any declared bound, and
 * losing it can only make two distinguishable bounds compare EQUAL — the admitted side of the law — so it
 * under-reports rather than refusing a model A12 takes.
 */
private fun JsonObject.number(member: String): Double? = (this[member] as? JsonPrimitive)?.doubleOrNull

private fun finding(field: DeclaredElement, code: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, field.path)
