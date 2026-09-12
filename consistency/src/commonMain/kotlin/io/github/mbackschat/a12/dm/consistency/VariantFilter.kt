package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A rule's or computation's `variantFilter` — the family whose main output is a COVERAGE GAP, not a finding.
 *
 * **An `ONLY` filter removes the declaration from A12's check entirely** ([KERNEL-FINDINGS KF299]). The same
 * declaration with a deliberately unresolvable reference is refused with `MVK_INVALID_ENTITY` unfiltered and
 * ADMITTED under `ONLY`, on both the rule and the computation carrier, measured rather than transferred. A12 returns no finding, no warning and no code, because
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
    suppressed().map { element ->
        CoverageGap(
            at = element.path,
            reason = "this declaration carries a `$SUPPRESSING_FILTER` variant filter, and A12 excludes " +
                "such a declaration from `checkConsistency` altogether — it was not examined here either, " +
                "and a clean verdict about this model does not cover it",
        )
    }

/**
 * The declarations A12 reaches no verdict about, so this checker reports none either.
 *
 * **Measured per break rather than transferred from the one reference row.** Seven ways of breaking a filtered
 * rule were run against the Kernel, unfiltered and under `ONLY` ([KERNEL-FINDINGS KF299],
 * `rulekit.validate.laws.RuleVariantFilterLawsTest`): an unresolvable condition reference, all three ways the
 * condition text can fail to parse, an error target naming no field, and an absent error message are ALL
 * admitted under the filter. There is no surviving subset to go on judging, which is why this suppresses the
 * whole declaration instead of one family — three predictions that the declaration's own slots would still be
 * judged were wrong, and a checker built on them would have reported six findings A12 does not make.
 *
 * **The seventh break is why [CK_MODEL_NOT_BUILDABLE] is exempt at the call site.** An empty `errorCode` still
 * refuses the model under `ONLY`, carrying no `MVK_*` code at all. A filter suppresses what A12 SAYS ABOUT a
 * declaration; it cannot rescue a model that fails to BUILD — so a finding that the model does not load is
 * not a verdict this filter can hide, and dropping it would report a model A12 refuses as merely unexamined.
 */
fun PreparedModel.variantFilterSuppressed(): Set<String> = suppressed().map { it.path }.toSet()

private fun PreparedModel.suppressed(): List<DeclaredElement> = filterable().filter { element ->
    val declared = element.body?.get("variantFilter")
    declared is JsonObject && declared.filterType() == FilterType.Spelled(SUPPRESSING_FILTER)
}

/** The shapes A12's own reader refuses, where the model never loads and no verdict about it exists. */
fun PreparedModel.variantFilterFindings(): List<Finding> =
    filterable().mapNotNull { element ->
        when (val declared = element.body?.get("variantFilter")) {
            // Absent, and an explicit `null`, are both ADMITTED: the member is reached through an `Optional`
            // accessor, unlike `errorCodesToSuppress` beside it, which is bound `@NonNull` and refuses null.
            null, JsonNull -> null
            is JsonObject -> when (val type = declared.filterType()) {
                FilterType.Absent -> unbuildable(
                    element,
                    "`variantFilter` declares no `filterType`, which A12 binds as non-null with no default, " +
                        "so the model fails while being constructed",
                )
                FilterType.NotAString -> unbuildable(
                    element,
                    "`variantFilter.filterType` is not a string, so A12's own reader cannot bind it",
                )
                is FilterType.Spelled ->
                    if (type.value in KNOWN_FILTER_TYPES) {
                        null
                    } else {
                        unbuildable(
                            element,
                            "`${type.value}` is not a variant filter type A12 knows — the types are " +
                                KNOWN_FILTER_TYPES.joinToString(" and ") { "`$it`" },
                        )
                    }
            }
            else -> unbuildable(
                element,
                "`variantFilter` is not a JSON object, so A12's own reader cannot bind it",
            )
        }
    }

/** The filter type A12 honours by NOT checking the declaration at all. */
private const val SUPPRESSING_FILTER = "ONLY"

private val KNOWN_FILTER_TYPES = setOf(SUPPRESSING_FILTER, "ALL_EXCEPT")

/**
 * What the `filterType` member turned out to be — three cases, because the member has three ways of being
 * wrong and a two-armed reading named the wrong defect for two of them.
 *
 * A missing member and a member holding a JSON object are different mistakes and A12 refuses them for
 * different reasons, while `JsonNull` is a [JsonPrimitive] in kotlinx whose `content` is the literal text
 * `null` — so a plain cast made `{"filterType": null}` indistinguishable from `{"filterType": "null"}` and
 * reported the one as the other.
 */
private sealed interface FilterType {
    /** The member is not there at all. */
    data object Absent : FilterType

    /** The member is there and is not a string — an object, a number, or JSON `null`. */
    data object NotAString : FilterType

    /** The member is a string, whether or not A12 knows the spelling. */
    data class Spelled(val value: String) : FilterType
}

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

private fun JsonObject.filterType(): FilterType = when (val declared = this["filterType"]) {
    null -> FilterType.Absent
    // JsonNull is a JsonPrimitive, so it must be refused BEFORE the primitive arm or its `content` reads back
    // as the four characters `null` and a missing value is reported as an unknown spelling.
    JsonNull -> FilterType.NotAString
    is JsonPrimitive -> if (declared.isString) FilterType.Spelled(declared.content) else FilterType.NotAString
    else -> FilterType.NotAString
}

private fun unbuildable(element: DeclaredElement, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain. A12 cannot build the model from this, so no consistency verdict about it exists",
    element.path,
)
