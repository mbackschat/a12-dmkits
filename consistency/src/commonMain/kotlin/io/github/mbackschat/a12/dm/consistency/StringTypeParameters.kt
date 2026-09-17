package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 *
 * **The length ordering has a second carrier**: a `CustomFieldType` declares the same two members under the
 * same law and A12 answers with a class of its own, unprefixed. Only that law is asked there — the rest of
 * this family has never been put to A12 on a custom type, and reusing it across the carrier would be a
 * refusal by analogy.
 */
internal fun TrackedFactBase.stringTypeParameterFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        field.stringTypeBody()?.let { body ->
            return@flatMap noValueValidationFindings(field, body) + lengthFindings(field, body)
        }
        // The custom type carries the same two length members under the same law and answers with a class of
        // its own — measured, not transferred. Its OTHER parameters are not judged here: nothing else on this
        // carrier has been put to A12, and a law reused across it would be a refusal by analogy.
        field.customTypeBody()?.let { body -> lengthFindings(field, body, MIN_ABOVE_MAX_ON_A_CUSTOM_TYPE) }
            ?: emptyList()
    }

/** The four laws that hold only when the type declares `noValueValidation`. */
private fun noValueValidationFindings(field: TrackedDeclaration, body: JsonObject): List<Finding> {
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

/**
 * The ordering law, and the line-break law that shares its members.
 *
 * [orderingCode] is the carrier's, because A12 spells this one law two ways: a string type draws
 * `MVK_MIN_LENGTH_BIGGER_MAX_LENGTH` and a custom type the unprefixed `MIN_LENGTH_BIGGER_MAX_LENGTH`.
 * The line-break law is the string carrier's alone — it has not been put to A12 on a custom type — so it is
 * asked only where a line-break flag is a member A12 reads.
 */
private fun lengthFindings(
    field: TrackedDeclaration,
    body: JsonObject,
    orderingCode: String = "MVK_MIN_LENGTH_BIGGER_MAX_LENGTH",
): List<Finding> {
    val min = body.declaredLength("minLength")
    val max = body.declaredLength("maxLength")
    val findings = mutableListOf<Finding>()
    if (min != null && max != null && min > max) {
        findings += finding(
            field,
            orderingCode,
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

/** A12's class for the same ordering law on the custom carrier, which carries no `MVK_` prefix. */
private const val MIN_ABOVE_MAX_ON_A_CUSTOM_TYPE = "MIN_LENGTH_BIGGER_MAX_LENGTH"

/** The custom type's body, or null when this field declares another type. */
private fun TrackedDeclaration.customTypeBody(): JsonObject? {
    val declared = body?.get("fieldType") as? JsonObject ?: return null
    if ((declared["type"] as? JsonPrimitive)?.content != "CustomFieldType") {
        return null
    }
    return declared["CustomFieldType"] as? JsonObject
}

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

private fun JsonObject.hintList(): List<*> = (this["hintList"] as? JsonArray) ?: emptyList<Any>()

private fun finding(field: TrackedDeclaration, code: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, field.path)
