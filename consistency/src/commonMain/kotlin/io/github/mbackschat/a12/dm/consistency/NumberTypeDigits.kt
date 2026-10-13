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
        lengthRangeFindings(field, body, admitsUndefined = false) + digitPairFindings(field, body) +
            valueRangeFindings(field, body) + budgetFindings(field, body)
    }

/** A12's declared-scale ceiling: 15 draws a codeless refusal on a surviving field (KF494). */
private const val MAX_SCALE = 14

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

private fun digitPairFindings(field: TrackedDeclaration, body: JsonObject): List<Finding> {
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
private fun budgetFindings(field: TrackedDeclaration, body: JsonObject): List<Finding> {
    val minFractional = body.int("minFractionalDigits") ?: 0
    val maxFractional = body.int("maxFractionalDigits") ?: 0
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
    findings += arityFindings(field, body, minFractional, maxFractional)
    findings += lengthFindings(field, body, minFractional, maxFractional)
    return findings
}

/**
 * The integer places, against the budget and against the fractional places beside them.
 *
 * The count A12 measures is not always the declared one: for `trait:"amount"` it derives
 * `15 - maxFractionalDigits` whenever `maxIntegerDigits` is absent (`TypeConverter.convertNumber`), so an
 * amount reaches this law through a member it never wrote. A count ABOVE the budget is refused one tier
 * earlier, with no class of its own, which is why the two outcomes are reported separately.
 *
 * A SUPPRESSED minimum is left unjudged here: A12 would sum a member its collector never wrote, and what
 * that sum is has not been measured. Under-reporting there is safe; guessing it is not.
 */
private fun arityFindings(
    field: TrackedDeclaration,
    body: JsonObject,
    minFractional: Int,
    maxFractional: Int,
): List<Finding> {
    val declared = body.int("maxIntegerDigits")
    val amount = body.string("trait") == "amount"
    val integerPlaces = declared ?: if (amount) DIGIT_BUDGET - maxFractional else return emptyList()
    if (integerPlaces > DIGIT_BUDGET) {
        val derived = if (declared == null) " (derived by A12 as `15 - maxFractionalDigits`)" else ""
        return listOf(
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                CK_MODEL_NOT_BUILDABLE,
                "this field allows $integerPlaces digits before the decimal point$derived and A12 caps " +
                    "them at $DIGIT_BUDGET, so A12 reads the model and then fails to build it — no " +
                    "consistency verdict about it exists, and A12 has no code of its own for this",
                field.path,
            ),
        )
    }
    if (minFractional < 0 || integerPlaces + minFractional <= DIGIT_BUDGET) {
        return emptyList()
    }
    return listOf(
        finding(
            field,
            "MVK_ARITY_TOO_BIG",
            "this field allows $integerPlaces digits before the decimal point and requires $minFractional " +
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
 * and at most 99999 and refuses anything else on its own ([lengthRangeFindings]).
 */
private fun lengthFindings(
    field: TrackedDeclaration,
    body: JsonObject,
    minFractional: Int,
    maxFractional: Int,
): List<Finding> {
    val min = body.declaredLength("minLength")
    val max = body.declaredLength("maxLength")
    val signed = body.flag("positivesOnly") != true
    val fractional = declaredPlaces(minFractional) != 0 || declaredPlaces(maxFractional) != 0
    val budget = DIGIT_BUDGET + (if (signed) 1 else 0) + (if (fractional) 1 else 0)
    val spent = "$DIGIT_BUDGET digits" +
        (if (signed) " plus a sign" else "") + (if (fractional) " plus a decimal point" else "")
    val findings = mutableListOf<Finding>()
    if (min != null && min > budget) {
        findings += finding(
            field,
            "MVK_MIN_LENGTH_TOO_BIG",
            "this field requires at least $min characters where A12 allows $budget — $spent",
        )
    }
    if (max != null && max > budget) {
        findings += finding(
            field,
            "MVK_MAX_LENGTH_TOO_BIG",
            "this field allows up to $max characters where A12 allows $budget — $spent",
        )
    }
    if (max != null && minFractional > 0 && max < minFractional + 2) {
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
