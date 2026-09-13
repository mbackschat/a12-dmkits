package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A declared string type's own parameters, against each other.
 *
 * Five of A12's codes, one topic, measured as one grid because a co-requirement measured member by member
 * yields a law that is false ([KERNEL-FINDINGS KF319],
 * `rulekit.validate.laws.StringTypeParameterLawsTest`). Four of the five hang off `noValueValidation` and
 * the fifth is the length ordering beside them.
 *
 * **`-1` is A12's own "no bound", not a bound** (`IFieldType.UNDEFINED`), and it decides three of the rows
 * here: a sentinel minimum is not a declared minimum for the exclusion law, a sentinel maximum is not a
 * maximum of one, and a real minimum beside a sentinel maximum is a minimum with no maximum rather than an
 * inverted range. Reading it as a number is what made a legal stored model unreadable through this
 * repository's own Java kit until 2026-09-13.
 *
 * **The laws are independent and this family reports them so**: a type with `noValueValidation`, no line
 * breaks and a pattern draws two codes from A12, not the first one it trips.
 *
 * **`maxLength` is NOT excluded by `noValueValidation`**, though its two siblings are — the row that keeps
 * this family from refusing a model A12 admits, and the reason the exclusion set is enumerated from the
 * grid rather than from the code's name.
 */
fun PreparedModel.stringTypeParameterFindings(): List<Finding> =
    elements.filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val body = field.stringTypeBody() ?: return@flatMap emptyList()
        noValueValidationFindings(field, body) + lengthFindings(field, body)
    }

/** The four laws that hold only when the type declares `noValueValidation`. */
private fun noValueValidationFindings(field: DeclaredElement, body: JsonObject): List<Finding> {
    if (body.flag("noValueValidation") != true) {
        return emptyList()
    }
    val excluded = EXCLUDED_BY_NO_VALUE_VALIDATION.filter { body.declares(it) }
    val findings = mutableListOf<Finding>()
    if (excluded.isNotEmpty()) {
        findings += finding(
            field,
            "MVK_STRING_TYPE_PARAMS_INCONSISTENT",
            "this field declares `noValueValidation` alongside ${excluded.joinToString(", ") { "`$it`" }}, " +
                "which A12 refuses — skipping value validation and constraining the value are the same " +
                "member's two answers. `maxLength` is deliberately absent from that set: A12 admits it here",
        )
    }
    if (body.hintList().isNotEmpty()) {
        findings += finding(
            field,
            "MVK_NO_VAL_AND_HINT_LISTS",
            "this field declares `noValueValidation` and a non-empty `hintList`, which A12 refuses; an " +
                "EMPTY hint list is admitted, so it is the entries rather than the member that conflict",
        )
    }
    if (body.flag("lineBreaksPermitted") != true) {
        findings += finding(
            field,
            "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK",
            "this field declares `noValueValidation` without `lineBreaksPermitted`; A12 requires the flag " +
                "to be set explicitly beside it, and reports this separately from any other parameter " +
                "conflict on the same type",
        )
    }
    return findings
}

/** The ordering law, and the line-break law that shares its members. */
private fun lengthFindings(field: DeclaredElement, body: JsonObject): List<Finding> {
    val min = body.declaredLength("minLength")
    val max = body.declaredLength("maxLength")
    val findings = mutableListOf<Finding>()
    if (min != null && max != null && min > max) {
        findings += finding(
            field,
            "MVK_MIN_LENGTH_BIGGER_MAX_LENGTH",
            "this field declares `minLength` $min above its `maxLength` $max, which A12 refuses; equal " +
                "bounds are admitted",
        )
    }
    if (body.flag("lineBreaksPermitted") == true && max == 1) {
        findings += finding(
            field,
            "MVK_LINE_BREAK_AND_MAX_LENGTH_1_INVALID",
            "this field permits line breaks in a value A12 caps at one character, which it refuses as " +
                "contradictory; either bound is admitted on its own",
        )
    }
    return findings
}

/**
 * The members `noValueValidation` excludes, enumerated from the kernel grid rather than from the code name.
 *
 * A12's own `StringType` declares no `template` member, so an earlier prose gloss of this code named one
 * that does not exist on this carrier; and `maxLength`, which reads as the obvious third, is ADMITTED.
 */
private val EXCLUDED_BY_NO_VALUE_VALIDATION = listOf("pattern", "minLength")

/** Whether the member is declared as something A12 reads as set — the sentinel counts as unset. */
private fun JsonObject.declares(member: String): Boolean = when (member) {
    "minLength", "maxLength" -> declaredLength(member) != null
    else -> this[member] != null
}

/**
 * The length this member declares, or null when it declares none.
 *
 * `-1` is A12's `IFieldType.UNDEFINED`, so a model may spell "no bound" either by omitting the member or by
 * writing the sentinel, and both are measured ADMITTED wherever a bound would have been refused.
 */
private fun JsonObject.declaredLength(member: String): Int? =
    (this[member] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

private fun JsonObject.flag(member: String): Boolean? = (this[member] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.hintList(): List<*> = (this["hintList"] as? JsonArray) ?: emptyList<Any>()

private fun finding(field: DeclaredElement, code: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, field.path)
