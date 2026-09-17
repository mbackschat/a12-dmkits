package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A computation's `roundingMode` and its alternatives' `toleranceRangeOp` — two stored members that look
 * independent and are the two halves of one A12 gate.
 *
 * Both are plain `String` in the stored model and both are bound to a closed set one layer down, so neither
 * is refused while the source is read and both can make the model unbuildable afterwards. The mode reaches
 * `IBaseCalculationService.RoundingMode.getByInternalValue`, whose `orElseThrow` ends the build; the band
 * reaches `ComputationAlternativesJoiner.determineLocalizedToleranceRangeOp`, whose `default ->` arm does the
 * same. **The admitted spellings are A12's INTERNAL values** — `Exact`/`RoundUp`/`RoundDown` and
 * `DiffersWithToleranceRange1`/`2`/`5`/`10` — matched exactly, so neither a constant name (`ROUND_UP`, `TR1`)
 * nor a different letter case is a spelling of anything.
 *
 * **The two are judged TOGETHER, and that is the whole reason this family exists apart from the slots.** An
 * alternative's band is what chooses the comparison the computation lowers to: absent gives `!=`, a band
 * gives the tolerance operator. A12's gate is `cmpOp == NE && roundingMode != EXACT` — both conjuncts — so a
 * rounding mode is never wrong by itself. Reading `RoundUp` as invalid on sight would refuse a model A12
 * accepts, which is measured rather than reasoned: the same mode that draws `MVK_ERROR_WRONG_ROUNDING_MODE`
 * against a plain `!=` is admitted against a tolerance band (`ComputationRoundingModeLawsTest`, kernel
 * 30.8.1, 2026-09-12).
 *
 * **The gate is owed by EVERY alternative, not by the computation.** The mode is declared once and each
 * alternative carries its own band, so one unbanded alternative among banded ones refuses the whole
 * computation — measured on the two-alternative carrier in either position, because a single-alternative
 * grid cannot tell "the computation's comparison" from "each alternative's comparison".
 *
 * **An explicit `null` is ABSENCE on both members**, which is the opposite of the rule slots beside them: a
 * rule's `errorCode`, `severity`, `errorCondition` and `errorEntityRelPath` are each refused by A12's reader
 * when spelled `null`, while these two are read through `Optional.ofNullable` accessors and simply default.
 * Measured on both rather than carried across from the neighbouring family.
 */
internal fun TrackedFactBase.computationRoundingFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.COMPUTATION }.flatMap { roundingFindings(it) }

/** A12's internal values for `IBaseCalculationService.RoundingMode`, which is what the member is matched against. */
private val ROUNDING_MODES = setOf("Exact", "RoundUp", "RoundDown")

/** The mode that imposes nothing — and the one an absent member defaults to. */
private const val EXACT = "Exact"

/** `md.model.a12internal.Computation.TR1`…`TR10`, the four wire spellings of a tolerance band. */
private val TOLERANCE_BANDS = setOf(
    "DiffersWithToleranceRange1",
    "DiffersWithToleranceRange2",
    "DiffersWithToleranceRange5",
    "DiffersWithToleranceRange10",
)

/**
 * One member bound to a closed set of strings, as far as A12 reads it.
 *
 * The three arms are three different tiers, which is why the member cannot be reduced to a boolean: [Unset]
 * is legal and takes the default, [Spelled] is a value the rest of the family reasons about, and [Refused]
 * means A12 never produced a verdict about this model at all.
 */
private sealed interface Bound {
    /** Absent, or an explicit `null` — both leave A12 holding the member's default. */
    data object Unset : Bound

    data class Spelled(val value: String) : Bound

    /** @param explain the member as authored, in the terms a report should name it by. */
    data class Refused(val explain: String) : Bound
}

private fun JsonObject.bound(member: String, admitted: Set<String>): Bound {
    val declared = this[member]
    if (declared == null || declared is JsonNull) {
        return Bound.Unset
    }
    // A scalar is COERCED to the String member, so `7` is the spelling "7" and fails the set the way an
    // unknown word does — which is why it is read for its content rather than dismissed as a non-string.
    // Only a CONTAINER has no spelling, and that one refuses A12's reader a tier earlier. Different tiers,
    // one code, because this checker owns exactly one and it covers both.
    val spelled = (declared as? JsonPrimitive)?.content
    if (spelled != null && spelled in admitted) {
        return Bound.Spelled(spelled)
    }
    return Bound.Refused(spelled?.let { "`$it`" } ?: "not a string")
}

private fun roundingFindings(element: TrackedDeclaration): List<Finding> {
    // An envelope A12 cannot bind is reported by the alternatives family, which meets it first; naming it
    // again here would report one unreadable computation twice.
    val body = element.body ?: return emptyList()
    val mode = body.bound("roundingMode", ROUNDING_MODES)
    val modeFindings = when (mode) {
        is Bound.Refused -> listOf(
            unbuildable(
                element,
                "`roundingMode` is ${mode.explain}, and A12 admits only " +
                    "${ROUNDING_MODES.joinToString(", ") { "`$it`" }} — omitting the member is what selects " +
                    "`$EXACT`",
            ),
        )
        else -> emptyList()
    }
    val alternatives = body.alternativeObjects() ?: return modeFindings
    val bands = alternatives.map { it.bound("toleranceRangeOp", TOLERANCE_BANDS) }
    val refused = bands.withIndex().filter { it.value is Bound.Refused }
    if (refused.isNotEmpty()) {
        return modeFindings + unbuildable(
            element,
            "${refused.joinToString(", ") { "alternative ${it.index + 1}'s `toleranceRangeOp` is " +
                (it.value as Bound.Refused).explain }} — A12 admits only " +
                "${TOLERANCE_BANDS.joinToString(", ") { "`$it`" }}, and omitting the member is what selects " +
                "a strict inequality",
        )
    }
    // Only now: a refused member above means A12 never judged this model, so its verdict on the PAIR does
    // not exist either and reporting one would present this checker's reading as A12's.
    return modeFindings + wrongRoundingModeFinding(element, mode, bands)
}

/**
 * The pair gate itself — a rounding mode other than `Exact` against an alternative that compares strictly.
 *
 * Reported once for the computation however many alternatives are unbanded, and the numbers go in the text:
 * the finding is this checker's report about a declaration, and claiming one notification per alternative
 * would assert something about A12's own reporting that no row here measures.
 */
private fun wrongRoundingModeFinding(
    element: TrackedDeclaration,
    mode: Bound,
    bands: List<Bound>,
): List<Finding> {
    val rounding = (mode as? Bound.Spelled)?.value?.takeIf { it != EXACT } ?: return emptyList()
    val strict = bands.withIndex().filter { it.value is Bound.Unset }.map { it.index + 1 }
    if (strict.isEmpty()) {
        return emptyList()
    }
    return listOf(
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_ERROR_WRONG_ROUNDING_MODE",
            "this computation rounds `$rounding`, and " +
                (if (strict.size == 1) "alternative ${strict.single()} declares" else
                    "alternatives ${strict.joinToString(", ")} declare") +
                " no `toleranceRangeOp`, so each compares exactly — A12 allows only `$EXACT` rounding " +
                "against an exact comparison. Give the alternative a tolerance band, or drop the rounding.",
            element.path,
        ),
    )
}

/**
 * The alternatives as objects, or absent when the member has a shape this family may not reason about.
 *
 * Returning nothing is deliberate rather than lenient: every such shape is already a finding of the
 * alternatives family, and a band cannot be read off a list A12 itself refuses.
 */
private fun JsonObject.alternativeObjects(): List<JsonObject>? {
    val member = this["computationAlternatives"] as? JsonArray ?: return null
    return member.map { it as? JsonObject ?: return null }
}

private fun unbuildable(element: TrackedDeclaration, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain. A12 cannot build the model from this, so no consistency verdict about it exists",
    element.path,
)
