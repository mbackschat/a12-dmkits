package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.predicates.NumberBound
import io.github.mbackschat.a12.dm.predicates.PrintedBoundDefect
import io.github.mbackschat.a12.dm.predicates.numberBoundExceedsDigitLimit
import io.github.mbackschat.a12.dm.predicates.numberBoundsInverted
import io.github.mbackschat.a12.dm.predicates.printedNumberBoundDefects
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val unbindable = unbindableBoundFindings(field, body)
        if (unbindable.isNotEmpty()) return@flatMap unbindable
        val digits = DigitCounts.of(body)
        memberRangeFindings(field, body.lengthMembers(admitsUndefined = false) + digits.ranged) +
            digitPairFindings(field, digits) + valueRangeFindings(field, body) + budgetFindings(field, body, digits)
    } + boundMagnitudeFindings()

/**
 * Each bound past the magnitude its fractional digits allow ([numberBoundExceedsDigitLimit]), on EVERY field.
 *
 * Unlike the laws above, this one is not judged on the default-variant model: A12 asks it of the stored model's
 * every field, so a field an `ONLY` filter deletes still draws it, measured ([KERNEL-FINDINGS KF582]). It is
 * independent of them too: it neither withholds nor is withheld by the printed-bound checks or the inversion. A
 * bound whose verdict depends on the JVM is left unjudged and disclosed by [boundMagnitudeGaps]. A count that is
 * present but not an integer is left unjudged as well, since what A12 binds it to is unmeasured.
 */
private fun TrackedFactBase.boundMagnitudeFindings(): List<Finding> = magnitudeVerdicts().mapNotNull { verdict ->
    verdict.takeIf { it.exceeded == true }?.let {
        finding(it.field, it.bound.code, "this field's `${it.bound.member}` of ${it.value} exceeds what A12 allows " +
            "beside a `maxFractionalDigits` of ${it.places ?: 0}: `10^(15 - k) - 10^(-k)`, ${it.bound.judged}")
    }
}

internal fun TrackedFactBase.boundMagnitudeGaps(): List<CoverageGap> = magnitudeVerdicts().mapNotNull { verdict ->
    verdict.takeIf { it.exceeded == null }?.let {
        CoverageGap(it.field.path, "this field's `${it.bound.member}` of ${it.value} lies within two ulps of A12's " +
            "magnitude limit for a `maxFractionalDigits` of ${it.places}, where `Math.pow` decides it differently " +
            "on different JVMs, so it was not judged")
    }
}

private class MagnitudeVerdict(
    val field: TrackedDeclaration, val bound: NumberBound, val value: Double, val places: Int?, val exceeded: Boolean?,
)

private fun TrackedFactBase.magnitudeVerdicts(): List<MagnitudeVerdict> =
    declarations().filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val body = field.numberTypeBody() ?: return@flatMap emptyList()
        val places = body.int("maxFractionalDigits")
        if (places == null && body["maxFractionalDigits"] != null) return@flatMap emptyList()
        NumberBound.entries.mapNotNull { bound ->
            body.number(bound.member)?.let { MagnitudeVerdict(field, bound, it, places, numberBoundExceedsDigitLimit(bound, it, places)) }
        }
    }

private val NumberBound.member: String get() = when (this) {
    NumberBound.MIN_VALUE -> "minValue"
    NumberBound.MAX_VALUE -> "maxValue"
}

private val NumberBound.code: String get() = when (this) {
    NumberBound.MIN_VALUE -> RK_NUMBER_MIN_VALUE_EXCEEDED
    NumberBound.MAX_VALUE -> RK_NUMBER_MAX_VALUE_EXCEEDED
}

private val NumberBound.judged: String get() = when (this) {
    NumberBound.MIN_VALUE -> "which a minimum may not exceed in absolute value"
    NumberBound.MAX_VALUE -> "which a maximum may not exceed"
}

/** A12's `DOCUMENT_MODEL_NUMBER_MIN_VALUE_EXCEEDED` and `…_MAX_VALUE_EXCEEDED`, which it states with no class key. */
internal const val RK_NUMBER_MIN_VALUE_EXCEEDED = "RK_NUMBER_MIN_VALUE_EXCEEDED"
internal const val RK_NUMBER_MAX_VALUE_EXCEEDED = "RK_NUMBER_MAX_VALUE_EXCEEDED"

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
            val maxFractional = fractional(body, "maxFractionalDigits")
            val declared = body.int("maxIntegerDigits")
            val integer = when {
                declared != null -> RangedMember("`maxIntegerDigits` $declared", declared, MemberRange.INTEGER_PLACES)
                body.string("trait") == "amount" -> (DIGIT_BUDGET - maxFractional.value).let {
                    RangedMember("$it integer places (derived by A12 as `15 - maxFractionalDigits`)", it, MemberRange.INTEGER_PLACES)
                }
                else -> null
            }
            return DigitCounts(integer, fractional(body, "minFractionalDigits"), maxFractional)
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

/**
 * The bounds' own formal checks, then the inversion law, which A12 withholds beside a refused bound because its
 * rule reads the refused value ([printedNumberBoundDefects]). Each sentence is reported once per field, however
 * many bounds fail it, as A12 reports it.
 */
private fun valueRangeFindings(field: TrackedDeclaration, body: JsonObject): List<Finding> {
    val maxFractional = body.int("maxFractionalDigits")
    val declared = listOfNotNull(body.number("minValue"), body.number("maxValue"))
    val defects = declared.flatMap { printedNumberBoundDefects(it, maxFractional) }.distinct()
    if (defects.isNotEmpty()) {
        return defects.sorted().map { boundDefectFinding(field, it) }
    }
    val min = body.number("minValue") ?: return emptyList()
    val max = body.number("maxValue") ?: return emptyList()
    if (!numberBoundsInverted(min, max, maxFractional)) {
        return emptyList()
    }
    return listOf(
        finding(
            field,
            "MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE",
            "this field declares a `minValue` of $min above its `maxValue` of $max, which A12 refuses; " +
                "equal bounds are admitted, and with no fractional digit allowed A12 compares both rounded " +
                "half away from zero to whole numbers",
        ),
    )
}

private fun boundDefectFinding(field: TrackedDeclaration, defect: PrintedBoundDefect): Finding {
    val (code, sentence) = when (defect) {
        PrintedBoundDefect.SIGNED_ZERO ->
            "RK_BOUND_SIGNED_ZERO" to "The value '0' as well as positive values must be provided without a sign."
        PrintedBoundDefect.TOO_MANY_DIGITS -> "RK_BOUND_TOO_MANY_DIGITS" to "Only values with up to 15 digits are allowed."
    }
    return finding(
        field,
        code,
        "A12 prints this field's `minValue` or `maxValue` — rounded to a whole number when no fractional digit " +
            "is allowed — and refuses the printed value with no class: \"$sentence\"",
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
    val min = body.lengthInRange("minLength")
    val max = body.lengthInRange("maxLength")
    val minFractional = digits.minFractional.value
    val positivesOnly = body.flag("positivesOnly")
    val signed = positivesOnly != true
    val fractionalPart = digits.fractionalPart()
    // An unbindable `positivesOnly` leaves the sign unknown, as an unknown fractional part leaves the point.
    val budgetKnown = fractionalPart != null && positivesOnly != null
    val fractional = fractionalPart == true
    val budget = DIGIT_BUDGET + (if (signed) 1 else 0) + (if (fractional) 1 else 0)
    val spent = "$DIGIT_BUDGET digits" +
        (if (signed) " plus a sign" else "") + (if (fractional) " plus a decimal point" else "")
    val findings = mutableListOf<Finding>()
    if (budgetKnown && min != null && min > budget) {
        findings += finding(
            field,
            "MVK_MIN_LENGTH_TOO_BIG",
            "this field requires at least $min characters where A12 allows $budget — $spent",
        )
    }
    if (budgetKnown && max != null && max > budget) {
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
private fun JsonObject.number(member: String): Double? = (this[member] as? JsonPrimitive)?.a12DecimalOrNull()

/**
 * A bound whose text A12's reader cannot bind ([A12DecimalBinding]) leaves no model, so no verdict about this field
 * exists and none of its other laws is stated.
 */
private fun unbindableBoundFindings(field: TrackedDeclaration, body: JsonObject): List<Finding> =
    listOf("minValue", "maxValue").filter { (body[it] as? JsonPrimitive)?.bindA12Decimal() == A12DecimalBinding.Unbindable }
        .map { member ->
            finding(
                field,
                CK_MODEL_NOT_BUILDABLE,
                "`$member` holds ${body[member]}, which A12's reader cannot bind as a decimal — the source is refused " +
                    "before any check runs, so no verdict about this model exists",
            )
        }

private fun finding(field: TrackedDeclaration, code: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, field.path)
