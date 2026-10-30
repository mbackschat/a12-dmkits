package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `variantFilter` member — the family whose main output is a COVERAGE GAP, not a finding.
 *
 * **Two scopes, because the member's two questions have different denominators.** Whether A12 can BIND a
 * filter is asked of every carrier that declares one (rule, computation, field); whether an `ONLY` filter
 * SUPPRESSES the declaration is asked of rules and computations alone, since on a field that spelling
 * deletes the field instead. Each function below names the scope it uses and why.
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
internal fun TrackedFactBase.variantFilterGaps(): List<CoverageGap> =
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
 * **The seventh break is why [VARIANT_BLIND_CODES] exist at the call site.** An empty `errorCode` still refuses
 * the model under `ONLY`, with the codeless sentence A12 states before it applies the filter ([KERNEL-FINDINGS
 * KF579]), and so does every other basic sentence ([KF626]). A source A12's reader refuses is not a verdict this
 * filter can hide either. A model that reads and then fails to BUILD the declaration is different: A12 never
 * converts a suppressed declaration, so an unbuildable computed target is lifted with it ([KF626]).
 */
internal fun TrackedFactBase.variantFilterSuppressed(): Set<String> = suppressed().map { it.path }.toSet()

private fun TrackedFactBase.suppressed(): List<TrackedDeclaration> = suppressible().filter { it.filtersItselfOut() }

/**
 * Whether this declaration's own `variantFilter` is the suppressing `ONLY` — the per-declaration half of
 * [variantFilterSuppressed], for a family judging one declaration, so that it reads that declaration's body alone
 * rather than every rule's and computation's.
 */
internal fun TrackedDeclaration.filtersItselfOut(): Boolean {
    val declared = body?.get("variantFilter")
    return declared is JsonObject && declared.filterType() == FilterType.Spelled(SUPPRESSING_FILTER)
}

/**
 * The shapes A12's own reader refuses, where the model never loads and no verdict about it exists.
 *
 * **This runs on all THREE carriers, where suppression runs on two** — and the split is the point. What an
 * `ONLY` filter MEANS differs by carrier: on a rule or computation it suppresses the declaration, on a field
 * it deletes the field ([KERNEL-FINDINGS KF305]), which is why [suppressed] stays narrow and the deletion is
 * handled at resolution instead. What a filter BINDS does not differ: every shape below answers alike on the
 * field carrier and on the rule carrier — measured on each rather than transferred, precisely because the
 * member's semantic law already proved carrier-specific, so nothing about it is uniform by default
 * (`rulekit.validate.laws.FieldVariantFilterLawsTest`, `RuleVariantFilterLawsTest`).
 */
internal fun TrackedFactBase.variantFilterFindings(): List<Finding> =
    bindable().mapNotNull { element ->
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
 * The declarations whose filter A12 SUPPRESSES the checking of — rules and computations, and not fields.
 *
 * A field's `ONLY` filter does something else entirely: `ValidationModelConverter` drops the field, so A12
 * says MORE about such a model rather than less, and that is handled where references resolve rather than by
 * suppressing anything. Including fields here would silence findings A12 actually makes.
 */
private fun TrackedFactBase.suppressible(): List<TrackedDeclaration> = declarations().filter {
    it.declaredKind == DeclaredKind.RULE || it.declaredKind == DeclaredKind.COMPUTATION
}

/**
 * Every carrier that DECLARES the member, which is the scope of the binding question.
 *
 * The three are enumerated from A12's own serializers: `variantFilter` is written by `FieldSerializer`,
 * `RuleSerializer` and `ComputationSerializer` and by no other. *Searched:*
 * `grep -rln variantFilter ../a12-kernel/kernel-md/kernel-md-serializer --include='*Serializer.java'` —
 * three files, all three read; there is no group carrier, which is why a group's stray `variantFilter` is
 * left alone here exactly as its stray `fieldType` is.
 */
private fun TrackedFactBase.bindable(): List<TrackedDeclaration> = declarations().filter {
    it.declaredKind == DeclaredKind.RULE || it.declaredKind == DeclaredKind.COMPUTATION ||
        it.declaredKind == DeclaredKind.FIELD
}

private fun JsonObject.filterType(): FilterType = when (val declared = this["filterType"]) {
    null -> FilterType.Absent
    // JsonNull is a JsonPrimitive, so it must be refused BEFORE the primitive arm or its `content` reads back
    // as the four characters `null` and a missing value is reported as an unknown spelling.
    JsonNull -> FilterType.NotAString
    is JsonPrimitive -> if (declared.isString) FilterType.Spelled(declared.content) else FilterType.NotAString
    else -> FilterType.NotAString
}

private fun unbuildable(element: TrackedDeclaration, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain. A12 cannot build the model from this, so no consistency verdict about it exists",
    element.path,
)
