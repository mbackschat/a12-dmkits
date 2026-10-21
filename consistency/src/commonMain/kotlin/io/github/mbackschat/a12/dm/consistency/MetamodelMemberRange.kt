package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A declared type member against the range A12's own metamodel admits for it (KERNEL-FINDINGS KF556, KF559).
 *
 * A12 judges a type's numeric members as numbers in a range and answers a member outside it with a sentence and
 * no class. Each sentence is reported once per field, however many members draw it — a length and a digit count
 * that are both signed draw one — and an out-of-range member is no bound for the field's other laws.
 *
 * A field deleted from the default variant is never judged: A12 is silent on it.
 */
internal fun memberRangeFindings(field: TrackedDeclaration, members: List<RangedMember>): List<Finding> =
    members.mapNotNull { member -> member.violation()?.let { it to member.label } }
        .groupBy({ it.first }, { it.second })
        .map { (violation, labels) ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                violation.code,
                "this field declares ${labels.joinToString(" and ")}, outside the range A12 admits; it refuses " +
                    "that with no class: \"${violation.sentence}\"",
                field.path,
            )
        }

/** One member's declared value and the range it is judged against, or no value when it declares none. */
internal class RangedMember(val label: String, val value: Int, val range: MemberRange) {
    fun violation(): MemberRangeViolation? = range.violationOf(value)
    val inRange: Boolean get() = violation() == null
}

/**
 * A member's admitted range: [lowest] to [highest], and `-1` when the member reads A12's "undefined".
 *
 * Below zero is always the signed sentence and zero below a [lowest] of 1 the positive one, so only the maximum
 * differs between members — each maximum is a sentence of its own.
 */
internal class MemberRange(
    private val lowest: Int,
    private val highest: Int,
    private val above: MemberRangeViolation,
    private val admitsUndefined: Boolean,
) {
    fun violationOf(value: Int): MemberRangeViolation? = when {
        value == UNDEFINED && admitsUndefined -> null
        value < 0 -> MemberRangeViolation.SIGNED
        value < lowest -> MemberRangeViolation.NOT_POSITIVE
        value > highest -> above
        else -> null
    }

    companion object {
        /**
         * `minLength` and `maxLength`: 1 to 99,999, the technical limit every stored value meets ([KF547]).
         * `-1` is "no bound" on the String and Custom types, which read `IFieldType.UNDEFINED`, and a signed
         * value on the Number type, whose metamodel member does not.
         */
        fun length(admitsUndefined: Boolean) =
            MemberRange(1, TECHNICAL_LENGTH_LIMIT, MemberRangeViolation.ABOVE_TECHNICAL_LIMIT, admitsUndefined)

        /** A number's integer places: 1 to A12's digit budget of 15, with no "undefined" spelling. */
        val INTEGER_PLACES = MemberRange(1, 15, MemberRangeViolation.ABOVE_DIGIT_BUDGET, admitsUndefined = false)

        /** A number's fractional places: 0 to the scale cap of 14, `-1` meaning "not declared". */
        val FRACTIONAL_PLACES = MemberRange(0, 14, MemberRangeViolation.ABOVE_SCALE_LIMIT, admitsUndefined = true)

        /** A group's `repeatability`: at least 1, the cap being a class of its own ([KF365]) rather than a sentence. */
        val REPEATABILITY = MemberRange(1, Int.MAX_VALUE, MemberRangeViolation.NOT_POSITIVE, admitsUndefined = false)
    }
}

/** The sentences, named by what they say rather than by the member, since A12 uses them for several. */
internal enum class MemberRangeViolation(val code: String, val sentence: String) {
    SIGNED("RK_MEMBER_SIGNED", "Only values without a sign are allowed."),
    NOT_POSITIVE("RK_MEMBER_NOT_POSITIVE", "Only values greater than 0 are allowed."),
    ABOVE_TECHNICAL_LIMIT("RK_MEMBER_ABOVE_TECHNICAL_LIMIT", "Only values smaller than or equal to 99999 are allowed."),
    ABOVE_DIGIT_BUDGET("RK_MEMBER_ABOVE_DIGIT_BUDGET", "Only values smaller than or equal to 15 are allowed."),
    ABOVE_SCALE_LIMIT("RK_MEMBER_ABOVE_SCALE_LIMIT", "Only values smaller than or equal to 14 are allowed."),
}

/** The two length members this type declares, each judged against [MemberRange.length]. */
internal fun JsonObject.lengthMembers(admitsUndefined: Boolean): List<RangedMember> =
    LENGTH_MEMBERS.mapNotNull { member ->
        intMember(member)?.let { RangedMember("`$member` $it", it, MemberRange.length(admitsUndefined)) }
    }

/** The length this member declares when A12 reads it as a bound at all, or null. */
internal fun JsonObject.lengthInRange(member: String): Int? = intMember(member)?.takeIf { it in 1..TECHNICAL_LENGTH_LIMIT }

private fun JsonObject.intMember(member: String): Int? = (this[member] as? JsonPrimitive)?.intOrNull

private val LENGTH_MEMBERS = listOf("minLength", "maxLength")

/** A12's `IFieldType.UNDEFINED`, its spelling of "no bound". */
internal const val UNDEFINED = -1

/** The longest String value A12 stores, and so the largest length it lets a model declare. */
private const val TECHNICAL_LENGTH_LIMIT = 99_999
