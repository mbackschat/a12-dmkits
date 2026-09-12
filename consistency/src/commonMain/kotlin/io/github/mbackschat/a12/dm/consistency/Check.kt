package io.github.mbackschat.a12.dm.consistency

/**
 * A family of required checks, named so a report can say which ones actually ran.
 *
 * The enum is the coverage denominator's live half. Its other half — the pinned profile's full list of
 * required families — does not exist yet, which is exactly why every report currently carries a gap saying
 * so: "the reference check completed" must never read as "the analysis completed".
 */
enum class CheckFamily {
    /** A rule's error target and a computation's computed field resolve to a declared element. */
    DECLARED_REFERENCES,

    /** Every entity named in a rule's condition or a computation's operations resolves. */
    CONDITION_REFERENCES,

    /**
     * A rule's and a computation's own declaration slots — the reserved name prefix and the error messages —
     * and the `header.locales` set those messages answer to, which decides what A12 collects from them.
     */
    DECLARATION_SLOTS,

    /**
     * A computation's `computationAlternatives` — that it carries work at all, and that each of several
     * alternatives carries its own precondition.
     */
    COMPUTATION_ALTERNATIVES,

    /** A rule's `errorCondition` and `errorEntityRelPath` are present and carry text. */
    RULE_CONDITION_AND_TARGET,

    /** No group holds two children of the same name, whatever kinds they are. */
    ELEMENT_NAME_UNIQUENESS,

    /** A group's `indexFieldName` and `sortFields[].name` each name one of its own fields. */
    GROUP_ORDERING_REFERENCES,

    /**
     * …and the field each of them names is one A12 will accept as an ordering key — never a string declared
     * `noValueValidation`. Its own constant because resolving and being usable are different claims, and a
     * report that merged them would let a resolution fix read as a type approval.
     */
    GROUP_ORDERING_FIELD_TYPE,

    /**
     * A computation's `roundingMode` and each alternative's `toleranceRangeOp` — that both spell a value
     * A12 knows, and that the pair they form is one A12 permits.
     */
    COMPUTATION_ROUNDING,

    /**
     * A computation's `errorCodesToSuppress` — that it renders into a pragma A12's parser can read, and that
     * the one code it may carry is the one A12 lets a computation suppress.
     */
    SUPPRESSED_WARNINGS,

    /**
     * A rule's or computation's `variantFilter` — that A12 can bind it, and WHICH declarations it takes out
     * of A12's check. The family is unusual in that its main output is a coverage gap rather than a finding,
     * because A12 accepts the models it concerns.
     */
    VARIANT_FILTER,
}

/**
 * A portion of the input that could not be examined, and why.
 *
 * There is deliberately no "which family" field. A gap is not one-to-one with a family — an unexpanded
 * mount blocks both reference families at once, and the unread declared members limit one without stopping
 * it — so a single slot could only ever be filled by guessing. [ConsistencyReport.checksCompleted] already
 * says which families ran; [reason] says what this particular gap costs.
 *
 * @param at the declaration it concerns, absent when the gap is the whole analysis.
 */
data class CoverageGap(val at: String?, val reason: String)

/**
 * What a consistency check produced.
 *
 * [findings] and [diagnostics] are deliberately separate: a finding is A12's verdict on a declaration, a
 * diagnostic is the checker saying it could not read something. Folding them together is the conflation
 * `ModelLoader.loadAndCheck` makes on the Java side, where an unreadable source arrives on the same channel
 * as an invalid declaration and a caller cannot tell them apart.
 *
 * @param modelId the source's own `header.id`, absent when admission could not read one.
 * @param checksCompleted the families that ran to completion — evidence, not a claim of sufficiency.
 */
data class ConsistencyReport(
    val modelId: String?,
    val status: ConsistencyStatus,
    val findings: List<Finding>,
    val diagnostics: List<Diagnostic>,
    val checksCompleted: Set<CheckFamily>,
    val gaps: List<CoverageGap>,
)

/**
 * Check one model source.
 *
 * The result can be INVALID or INCONCLUSIVE and never VALID, because no evidence exists that the pinned
 * profile's required checks ran — only that the one implemented family did. That bar lifts with the coverage
 * registry, not with an added enum constant (PORTABLE-CONSISTENCY-PROPOSAL §7, "the first one-check slice
 * cannot emit VALID by construction").
 *
 * An unreadable source yields a report rather than a raised exception, with its failure stated at the source
 * level rather than attributed to a rule that was never read.
 */
fun check(source: ModelSource): ConsistencyReport {
    val admitted = when (val admission = admit(source)) {
        is Admission.Rejected -> return ConsistencyReport(
            modelId = null,
            status = ConsistencyStatus.Inconclusive,
            findings = emptyList(),
            diagnostics = listOf(admission.diagnostic),
            checksCompleted = emptySet(),
            gaps = listOf(CoverageGap(null, "the source could not be admitted, so nothing was checked")),
        )
        is Admission.Admitted -> admission
    }
    return check(admitted, providers = emptyMap(), inherited = emptyList())
}

/**
 * Check one already-admitted entry model against the [providers] its includes may name.
 *
 * [inherited] are diagnostics the caller already holds about the source set itself — a source that could not
 * be admitted, an id supplied twice. They ride into the report because they are usually the CAUSE of a mount
 * this analysis then reports as unexpanded, and a consumer shown only the consequence cannot act on it.
 */
internal fun check(
    admitted: Admission.Admitted,
    providers: Map<String, Admission.Admitted>,
    inherited: List<Diagnostic>,
): ConsistencyReport {
    val prepared = prepare(admitted, providers)
    prepared.unsupportedConditionLanguage?.let { code ->
        // A12 refuses the model rather than falling back to a language, so there is no reading of its
        // conditions to have — every keyword question would be answered in a language A12 never applies.
        return ConsistencyReport(
            modelId = admitted.modelId,
            status = ConsistencyStatus.Invalid,
            findings = listOf(
                Finding(
                    FindingCategory.A12_CONSTRAINT,
                    FindingSeverity.ERROR,
                    CK_MODEL_NOT_BUILDABLE,
                    "`$code` is not a condition language A12 supports, so the model cannot be used at all",
                ),
            ),
            diagnostics = inherited + prepared.diagnostics,
            checksCompleted = emptySet(),
            gaps = prepared.gaps() + UNIMPLEMENTED_FAMILIES,
        )
    }
    val unmounted = prepared.unexpandedMounts.map {
        CoverageGap(
            at = it.at,
            reason = "this group mounts `${it.alias}` and the mount is not expanded here (${it.reason}), so " +
                "the fact base is incomplete and no reference check can run over this source",
        )
    }
    if (unmounted.isNotEmpty()) {
        // The fact base is INCOMPLETE, so "no declared element matches" is not a verdict this checker may
        // reach: the elements are declared, in a model this source does not carry. Reporting the references
        // anyway would reject a model A12 accepts, which is the one direction a checker must never take.
        return ConsistencyReport(
            modelId = admitted.modelId,
            status = ConsistencyStatus.Inconclusive,
            findings = emptyList(),
            diagnostics = inherited + prepared.diagnostics,
            checksCompleted = emptySet(),
            gaps = prepared.gaps() + unmounted + UNIMPLEMENTED_FAMILIES,
        )
    }
    val declared = prepared.declaredReferences()
    val findings = declared.mapNotNull { it.target.asFinding(it.role, it.declaredAt) } +
        prepared.conditionReferences().mapNotNull {
            it.target.asFinding(ReferenceRole.CONDITION_ENTITY, it.declaredAt, it.member)
        } + prepared.declarationSlotFindings() + prepared.computationAlternativeFindings() +
        prepared.computationRoundingFindings() + prepared.suppressedWarningFindings() +
        prepared.ruleConditionAndTargetFindings() + prepared.variantFilterFindings() +
        prepared.elementNameFindings() + prepared.groupOrderingFindings()
    return ConsistencyReport(
        modelId = admitted.modelId,
        status = statusOf(findings),
        findings = findings,
        diagnostics = inherited + prepared.diagnostics,
        checksCompleted = setOf(CheckFamily.DECLARED_REFERENCES, CheckFamily.CONDITION_REFERENCES,
            CheckFamily.DECLARATION_SLOTS, CheckFamily.COMPUTATION_ALTERNATIVES,
            CheckFamily.RULE_CONDITION_AND_TARGET, CheckFamily.ELEMENT_NAME_UNIQUENESS,
            CheckFamily.GROUP_ORDERING_REFERENCES, CheckFamily.GROUP_ORDERING_FIELD_TYPE,
            CheckFamily.COMPUTATION_ROUNDING, CheckFamily.SUPPRESSED_WARNINGS,
            CheckFamily.VARIANT_FILTER),
        gaps = prepared.gaps() + prepared.emptyFactBase() + findings.buildBlocked() +
            prepared.variantFilterGaps() + UNREAD_MEMBERS + UNIMPLEMENTED_FAMILIES,
    )
}

/**
 * A source that declares nothing produces a report indistinguishable from one whose references all resolve,
 * so it says which of the two it is.
 *
 * This is a GAP and not a rejection because A12 accepts such a model: a type-def-only library is exactly an
 * empty root and is Kernel-valid standalone, and the Kernel reads an absent `rootGroups` and an absent
 * `modelRoot` too (measured 2026-09-12, `ModelRootReadabilityLawsTest`). The one shape it cannot read —
 * a missing `content` — is refused a phase earlier, at admission.
 */
private fun PreparedModel.emptyFactBase(): List<CoverageGap> = if (elements.isNotEmpty()) {
    emptyList()
} else {
    listOf(
        CoverageGap(
            at = null,
            reason = "this source declares no elements, so no rule or computation was examined — a report " +
                "with no findings here means nothing was found to check, not that nothing was wrong",
        ),
    )
}

/** A declaration the checker could not read was not checked either, so each one is also a coverage gap. */
private fun PreparedModel.gaps(): List<CoverageGap> = diagnostics.map {
    CoverageGap(it.at, "this declaration could not be read, so no check ran over it: ${it.explain}")
}

/**
 * The findings that stop A12 from producing a verdict at all, so every OTHER finding in the same report is
 * this checker's own reading rather than something A12 would have said about this model.
 *
 * They are recognized by their code, because the class is wider than any one family: an unresolvable
 * computed field and a target climbing past the root stop the Kernel from BUILDING the model (measured
 * through `ModelLoader.loadAndCheck`), while a message entry or a locale code its READER refuses stops it
 * one tier earlier still. A consumer told only the findings would assume the list is A12's; the gap says
 * which it is.
 */
private fun List<Finding>.buildBlocked(): List<CoverageGap> =
    filter { it.code == CK_MODEL_NOT_BUILDABLE }.map {
        CoverageGap(
            at = it.at,
            reason = "this makes the model unusable, so A12 reports that and stops; every other finding " +
                "here is this checker's own reading of a model A12 never got far enough to judge",
        )
    }

/**
 * What this checker reads only PART of. Stated on every report rather than only in the plan, because a
 * consumer cannot otherwise tell a clean result from an unexamined one.
 */
private val UNREAD_MEMBERS = listOf(
    CoverageGap(
        at = null,
        reason = "a computation alternative's precondition is checked for PRESENCE only — whether its text " +
            "parses is the syntax family's question and is not decided here",
    ),
    CoverageGap(
        at = null,
        reason = "a group's index/sort field is judged on its `StringType` body alone — a `fieldType` that " +
            "carries the `noValueValidation` flag anywhere else is left unjudged, because A12 reads that " +
            "flag only after deciding the type IS a string and no row measures the other shapes",
    ),
    CoverageGap(
        at = null,
        reason = "a member holding a JSON container where A12 binds a string — a computation's `operation` " +
            "or `precondition`, a rule's `errorCondition` or `errorEntityRelPath`, a group's " +
            "`indexFieldName`, a non-array `sortFields` — is left unjudged rather than guessed at; each is " +
            "unmeasured, and under-reporting is the only safe direction",
    ),
)

private val UNIMPLEMENTED_FAMILIES = listOf(
    CoverageGap(
        at = null,
        reason = "only the families named by `CheckFamily` are implemented; every other required family of " +
            "the pinned profile was not examined",
    ),
)
