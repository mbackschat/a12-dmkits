package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A declared `minLength` or `maxLength` against the range A12's own metamodel admits for it (KERNEL-FINDINGS
 * KF556, `adapter.laws.CheckerLengthLimitAgreementTest`).
 *
 * A12 judges the two members of a String, Custom or Number type as numbers from 1 to 99,999, the technical limit
 * every stored value meets ([KF547]), and answers a member outside that with one of three sentences and no class.
 * Each sentence is reported once per field, however many members draw it, and an out-of-range member is no bound
 * for the field's other laws: it neither orders against its sibling nor conflicts with `noValueValidation`.
 *
 * **`-1` is A12's "no bound" on String and Custom, and a signed value on Number.** The two carriers that read
 * `IFieldType.UNDEFINED` admit it; the Number type's metamodel member does not.
 *
 * A field deleted from the default variant is never judged: A12 is silent on it.
 */
internal fun lengthRangeFindings(field: TrackedDeclaration, body: JsonObject, admitsUndefined: Boolean): List<Finding> =
    LENGTH_MEMBERS.mapNotNull { member -> body.lengthValue(member)?.let { member to it } }
        .mapNotNull { (member, value) -> LengthRangeViolation.of(value, admitsUndefined)?.let { it to "`$member` $value" } }
        .groupBy({ it.first }, { it.second })
        .map { (violation, members) ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                violation.code,
                "this field declares ${members.joinToString(" and ")}, outside the 1 to $TECHNICAL_LENGTH_LIMIT " +
                    "A12 admits for a length; it refuses that with no class: \"${violation.sentence}\"",
                field.path,
            )
        }

/** The length this member declares when A12 reads it as a bound at all, or null. */
internal fun JsonObject.lengthInRange(member: String): Int? = lengthValue(member)?.takeIf { it in 1..TECHNICAL_LENGTH_LIMIT }

private fun JsonObject.lengthValue(member: String): Int? = (this[member] as? JsonPrimitive)?.intOrNull

/** The three sentences, named by what they say rather than by the member, since A12 uses them for others too. */
private enum class LengthRangeViolation(val code: String, val sentence: String) {
    SIGNED("RK_MEMBER_SIGNED", "Only values without a sign are allowed."),
    NOT_POSITIVE("RK_MEMBER_NOT_POSITIVE", "Only values greater than 0 are allowed."),
    ABOVE_TECHNICAL_LIMIT("RK_MEMBER_ABOVE_TECHNICAL_LIMIT", "Only values smaller than or equal to 99999 are allowed."),
    ;

    companion object {
        fun of(value: Int, admitsUndefined: Boolean): LengthRangeViolation? = when {
            value == UNDEFINED && admitsUndefined -> null
            value < 0 -> SIGNED
            value == 0 -> NOT_POSITIVE
            value > TECHNICAL_LENGTH_LIMIT -> ABOVE_TECHNICAL_LIMIT
            else -> null
        }
    }
}

private val LENGTH_MEMBERS = listOf("minLength", "maxLength")

/** A12's `IFieldType.UNDEFINED`. */
private const val UNDEFINED = -1

/** The longest String value A12 stores, and so the largest length it lets a model declare. */
private const val TECHNICAL_LENGTH_LIMIT = 99_999
