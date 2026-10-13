package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A declared number type's fractional-digit pair and its value range.
 *
 * Measured as one grid ([KERNEL-FINDINGS KF320], `rulekit.validate.laws.NumberTypeDigitLawsTest`), and the
 * digit law is not the one its name suggests. **An ABSENT member reads as 0 and a `-1` maximum takes the
 * pair out of the check** — so `minFractionalDigits: 2` with no maximum IS refused, while the same minimum
 * beside an explicit `-1` is admitted. A plain `min > max` over the declared members disagrees with A12 on
 * both of those, in opposite directions.
 *
 * The value range beside it is the ordinary law: both bounds declared, equal admitted, inverted refused.
 *
 * **Each digit count has a range of its own, judged before any of these laws** ([KERNEL-FINDINGS KF559],
 * `adapter.laws.CheckerDigitMemberRangeAgreementTest`): integer places 1 to 15, fractional places 0 to 14 with
 * `-1` for "not declared". A count outside draws a codeless sentence through [memberRangeFindings], together
 * with the lengths so each sentence stays once per field, and it is no bound for a law that reads it.
 *
 * **Five further laws measure the same declaration against A12's model-wide DIGIT BUDGET of 15**
 * ([KERNEL-FINDINGS KF364], same lock): the fractional pair must be declared at all, the integer and
 * fractional places together must fit the budget, and each length must fit it plus a place for a sign and a
 * place for a decimal point. Two neighbours of theirs are NOT reachable from a stored model and are
 * deliberately silent — `MVK_AMOUNT_INT_PART_MISSING`, whose member A12 derives for every amount that
 * declares none, and the priority half of the sort block that has no DM-JSON member at all.
 */
internal fun TrackedFactBase.numberTypeDigitFindings(): List<Finding> =
    defaultVariantFields().flatMap { field ->
        val body = field.numberTypeBody() ?: return@flatMap emptyList()
        val digits = DigitCounts.of(body)
        memberRangeFindings(field, body.lengthMembers(admitsUndefined = false) + digits.ranged) +
            digitPairFindings(field, digits) + valueRangeFindings(field, body) + budgetFindings(field, body, digits)
    }

/**
 * The three digit counts as A12's metamodel holds them, each judged against its range.
 *
 * An ABSENT fractional count is the primitive 0. The integer count of an amount that declares none is DERIVED
 * as `15 - maxFractionalDigits` (`TypeConverter.convertNumber`) from whatever the maximum holds, in range or
 * not, and the derived count is judged like a declared one: a maximum of 15 derives 0 and draws both the scale
 * and the positive sentence.
 */
private class DigitCounts(val integer: RangedMember?, val minFractional: RangedMember, val maxFractional: RangedMember) {
    val ranged: List<RangedMember> get() = listOfNotNull(integer, minFractional, maxFractional)

    /**
     * Whether a fractional part is declared, as A12 reads it: an OR whose out-of-range operand is unknown.
     *
     * Measured both ways, and neither simpler reading survives both: a minimum of 15 beside a maximum of 2
     * still answers yes, while a minimum of `-2` beside a maximum of 0 answers nothing, so a law asking it
     * stays silent.
     */
    fun fractionalPart(): Boolean? {
        val parts = listOf(minFractional, maxFractional).map { if (it.inRange) declaredPlaces(it.value) != 0 else null }
        return when {
            true in parts -> true
            null in parts -> null
            else -> false
        }
    }

    companion object {
        fun of(body: JsonObject): DigitCounts {
            val maxFractional = body.int("maxFractionalDigits") ?: 0
            val declared = body.int("maxIntegerDigits")
            val integer = when {
                declared != null -> RangedMember("`maxIntegerDigits` $declared", declared, MemberRange.INTEGER_PLACES)
                body.string("trait") == "amount" -> (DIGIT_BUDGET - maxFractional).let {
                    RangedMember("$it integer places (derived by A12 as `15 - maxFractionalDigits`)", it, MemberRange.INTEGER_PLACES)
                }
                else -> null
            }
            return DigitCounts(integer, fractional(body, "minFractionalDigits"), fractional(body, "maxFractionalDigits"))
        }

        private fun fractional(body: JsonObject, member: String): RangedMember =
            (body.int(member) ?: 0).let { RangedMember("`$member` $it", it, MemberRange.FRACTIONAL_PLACES) }
    }
}

/**
 * A12's model-wide digit budget for a number type, measured: 15.
 *
 * It is a member of A12's METAMODEL document (`/MVK/ModelProperties/maxDigits`) rather than of a stored
 * model, so a stored model cannot vary it. The value is derived from the thresholds rather than read: a
 * `maxLength` of 16 is admitted on a signed number, refused on a signless one, and 17 is admitted once a
 * fractional part is declared.
 */
private const val DIGIT_BUDGET = 15

/** A12's own "not declared" spelling for the two fractional counts, which are otherwise primitive. */
private const val SUPPRESSED = -1

private fun digitPairFindings(field: TrackedDeclaration, digits: DigitCounts): List<Finding> {
    if (!digits.minFractional.inRange || !digits.maxFractional.inRange) {
        return emptyList()
    }
    val min = digits.minFractional.value
    val max = digits.maxFractional.value
    if (max < 0 || min <= max) {
        return emptyList()
    }
    return listOf(
        finding(
            field,
            "MVK_FRACT_DIGITS_INVALID",
            "this field declares $min minimum fractional digits above its maximum of $max, which A12 " +
                "refuses. A12 reads an ABSENT `maxFractionalDigits` as 0, so a minimum with no maximum is " +
                "this same inversion",
        ),
    )
}

private fun valueRangeFindings(field: TrackedDeclaration, body: JsonObject): List<Finding> {
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

/**
 * The five laws that measure this declaration against [DIGIT_BUDGET].
 *
 * **Every one of them reads the fractional pair, and [SUPPRESSED] is what the pair means by "not
 * declared".** An ABSENT member is the primitive 0 and counts as declared; only an explicit `-1` is
 * withheld from A12's metamodel document. So the missing-pair law needs BOTH members suppressed, and a
 * suppressed member costs nothing in the length threshold — measured, because reading it as "some
 * fractional part" would allow one length too many.
 */
private fun budgetFindings(field: TrackedDeclaration, body: JsonObject, digits: DigitCounts): List<Finding> {
    val minFractional = digits.minFractional.value
    val maxFractional = digits.maxFractional.value
    val findings = mutableListOf<Finding>()
    if (minFractional == SUPPRESSED && maxFractional == SUPPRESSED) {
        findings += finding(
            field,
            "MVK_FRACT_DIGITS_MISSING",
            "this field suppresses both `minFractionalDigits` and `maxFractionalDigits` with `-1`, and A12 " +
                "requires a number to declare its fractional digits. Declaring either as 0 is a " +
                "declaration; omitting the member entirely is read as 0 and is admitted",
        )
    }
    findings += arityFindings(field, digits)
    findings += lengthFindings(field, body, digits)
    return findings
}

/**
 * The integer places against the fractional places beside them.
 *
 * The count A12 measures is not always the declared one: for `trait:"amount"` it is derived ([DigitCounts]),
 * so an amount reaches this law through a member it never wrote. A count outside its range is no bound here,
 * so the law is reachable only from a count within the budget that a fractional part then pushes past it.
 *
 * A SUPPRESSED minimum is left unjudged here: A12 would sum a member its collector never wrote, and what
 * that sum is has not been measured. Under-reporting there is safe; guessing it is not.
 */
private fun arityFindings(field: TrackedDeclaration, digits: DigitCounts): List<Finding> {
    val integer = digits.integer ?: return emptyList()
    val minFractional = digits.minFractional.value
    if (!integer.inRange || !digits.minFractional.inRange || minFractional < 0 ||
        integer.value + minFractional <= DIGIT_BUDGET
    ) {
        return emptyList()
    }
    return listOf(
        finding(
            field,
            "MVK_ARITY_TOO_BIG",
            "this field allows ${integer.value} digits before the decimal point and requires $minFractional " +
                "after it, which is more than A12's budget of $DIGIT_BUDGET digits",
        ),
    )
}

/**
 * The two length bounds against the budget, and the two laws that relate them to each other.
 *
 * The threshold is `15 + a sign + a decimal point`: a signed number may spend one place on the sign, and a
 * declared fractional part one on the separator. The sign is `!positivesOnly`, which A12 defaults to
 * present.
 *
 * Only a length A12's own metamodel would accept as a length is judged — it declares both members positive
 * and at most 99999 and refuses anything else on its own ([memberRangeFindings]). The two budget laws ask
 * whether a fractional part is declared and stay silent when that is unknown ([DigitCounts.fractionalPart]).
 */
private fun lengthFindings(field: TrackedDeclaration, body: JsonObject, digits: DigitCounts): List<Finding> {
    val min = body.declaredLength("minLength")
    val max = body.declaredLength("maxLength")
    val minFractional = digits.minFractional.value
    val signed = body.flag("positivesOnly") != true
    val fractionalPart = digits.fractionalPart()
    val fractional = fractionalPart == true
    val budget = DIGIT_BUDGET + (if (signed) 1 else 0) + (if (fractional) 1 else 0)
    val spent = "$DIGIT_BUDGET digits" +
        (if (signed) " plus a sign" else "") + (if (fractional) " plus a decimal point" else "")
    val findings = mutableListOf<Finding>()
    if (fractionalPart != null && min != null && min > budget) {
        findings += finding(
            field,
            "MVK_MIN_LENGTH_TOO_BIG",
            "this field requires at least $min characters where A12 allows $budget — $spent",
        )
    }
    if (fractionalPart != null && max != null && max > budget) {
        findings += finding(
            field,
            "MVK_MAX_LENGTH_TOO_BIG",
            "this field allows up to $max characters where A12 allows $budget — $spent",
        )
    }
    if (max != null && digits.minFractional.inRange && minFractional > 0 && max < minFractional + 2) {
        findings += finding(
            field,
            "MVK_MAX_LENGTH_TOO_SMALL",
            "this field allows up to $max characters and requires $minFractional of them after the " +
                "decimal point, leaving no room for the point and a leading digit",
        )
    }
    if (min != null && max != null && min > max) {
        findings += finding(
            field,
            "MVK_MIN_LENGTH_BIGGER_MAX_LENGTH",
            "this field declares `minLength` $min above its `maxLength` $max, which A12 refuses; equal " +
                "bounds are admitted",
        )
    }
    return findings
}

/** A fractional count as the threshold reads it: the suppression sentinel spends nothing. */
private fun declaredPlaces(count: Int): Int = if (count == SUPPRESSED) 0 else count

private fun JsonObject.declaredLength(member: String): Int? = lengthInRange(member)

/** The `NumberType` body this field declares, or null when it declares another type or none. */
private fun TrackedDeclaration.numberTypeBody(): JsonObject? {
    val declared = body?.get("fieldType") as? JsonObject ?: return null
    if (declared.namedFieldType() != FieldType.NUMBER) {
        return null
    }
    return FieldType.NUMBER.bodyIn(declared)
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

private fun finding(field: TrackedDeclaration, code: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, field.path)
