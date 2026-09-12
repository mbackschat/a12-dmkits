package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A rule's or computation's `variantFilter` — the family whose main output is a COVERAGE GAP, not a finding.
 *
 * **An `ONLY` filter removes the declaration from A12's check entirely** ([KERNEL-FINDINGS KF299]). The same
 * declaration with a deliberately unresolvable reference is refused with `MVK_INVALID_ENTITY` unfiltered and
 * ADMITTED under `ONLY`, on both the rule and the computation carrier — sixteen cells each, measured rather
 * than transferred. A12 returns no finding, no warning and no code, because
 * `consistency.rules.CodeGenerationRule.checkModel` generates validation code for the default variant and
 * then merely `LOG.warn`s that the check may be incomplete.
 *
 * **So the response is a gap and never a Finding.** A12 ACCEPTS such a model; reporting an ERROR would reject
 * what the Kernel admits, which is the one direction this checker must never take. The gap states the true
 * and more useful thing: this declaration was not examined by anyone, so a clean report does not cover it.
 *
 * **The filter TYPE alone decides and the named variants are irrelevant** — `ALL_EXCEPT` is judged normally
 * even when it excludes the default variant, and `ONLY` suppresses even when it names the default variant,
 * an empty list, or no `variants` member at all. Two rival accounts died on that grid: "any filter
 * suppresses" fails on the `ALL_EXCEPT` rows, and "exclusion from the default variant suppresses" fails on
 * `ONLY [""]`. This code therefore keys on the type and never inspects the list — reading the list would
 * reintroduce exactly the account the measurement killed.
 */
fun PreparedModel.variantFilterGaps(): List<CoverageGap> =
    filterable().mapNotNull { element ->
        val declared = element.body?.get("variantFilter")
        if (declared is JsonObject && declared.filterType() == SUPPRESSING_FILTER) {
            CoverageGap(
                at = element.path,
                reason = "this declaration carries a `$SUPPRESSING_FILTER` variant filter, and A12 excludes " +
                    "such a declaration from `checkConsistency` altogether — it was not examined here either, " +
                    "and a clean verdict about this model does not cover it",
            )
        } else {
            null
        }
    }

/** The shapes A12's own reader refuses, where the model never loads and no verdict about it exists. */
fun PreparedModel.variantFilterFindings(): List<Finding> =
    filterable().mapNotNull { element ->
        when (val declared = element.body?.get("variantFilter")) {
            // Absent, and an explicit `null`, are both ADMITTED: the member is reached through an `Optional`
            // accessor, unlike `errorCodesToSuppress` beside it, which is bound `@NonNull` and refuses null.
            null, JsonNull -> null
            is JsonObject -> when (declared.filterType()) {
                null -> unbuildable(
                    element,
                    "`variantFilter` declares no `filterType`, which A12 binds as non-null with no default, " +
                        "so the model fails while being constructed",
                )
                in KNOWN_FILTER_TYPES -> null
                else -> unbuildable(
                    element,
                    "`${declared.filterType()}` is not a variant filter type A12 knows — the types are " +
                        "${KNOWN_FILTER_TYPES.joinToString(" and ") { "`$it`" }}",
                )
            }
            else -> unbuildable(
                element,
                "`variantFilter` is not a JSON object, so A12's own reader cannot bind it",
            )
        }
    }

/** The filter type A12 honours by NOT checking the declaration at all. */
private const val SUPPRESSING_FILTER = "ONLY"

private val KNOWN_FILTER_TYPES = listOf(SUPPRESSING_FILTER, "ALL_EXCEPT")

/**
 * The declarations this family judges.
 *
 * `variantFilter` also rides FIELD elements (`serializer.FieldSerializer` writes it), a carrier outside this
 * checker's rules-and-computations subject matter and unmeasured — so it is left alone rather than judged by
 * analogy.
 */
private fun PreparedModel.filterable(): List<DeclaredElement> = elements.filter {
    it.declaredKind == DeclaredKind.RULE || it.declaredKind == DeclaredKind.COMPUTATION
}

private fun JsonObject.filterType(): String? = (this["filterType"] as? JsonPrimitive)?.content

private fun unbuildable(element: DeclaredElement, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain. A12 cannot build the model from this, so no consistency verdict about it exists",
    element.path,
)
