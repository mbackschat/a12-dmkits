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
}

/**
 * A portion of the input that could not be examined, and why.
 *
 * @param at the declaration it concerns, absent when the gap is the whole analysis.
 * @param blocked the family that could not run, absent when no family is implemented for it at all.
 */
data class CoverageGap(val at: String?, val blocked: CheckFamily?, val reason: String)

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
            gaps = listOf(CoverageGap(null, null, "the source could not be admitted, so nothing was checked")),
        )
        is Admission.Admitted -> admission
    }
    val prepared = prepare(admitted)
    val declared = prepared.declaredReferences()
    val findings = declared.mapNotNull { it.target.asFinding(it.role, it.declaredAt) } +
        prepared.conditionReferences().mapNotNull {
            it.target.asFinding(ReferenceRole.CONDITION_ENTITY, it.declaredAt)
        }
    return ConsistencyReport(
        modelId = admitted.modelId,
        status = statusOf(findings),
        findings = findings,
        diagnostics = prepared.diagnostics,
        checksCompleted = setOf(CheckFamily.DECLARED_REFERENCES, CheckFamily.CONDITION_REFERENCES),
        gaps = prepared.gaps() + declared.buildBlocked() + UNIMPLEMENTED_FAMILIES,
    )
}

/** A declaration the checker could not read was not checked either, so each one is also a coverage gap. */
private fun PreparedModel.gaps(): List<CoverageGap> = diagnostics.map {
    CoverageGap(it.at, null, "this declaration could not be read, so no check ran over it: ${it.explain}")
}

/**
 * An unresolvable computed field stops the Kernel from BUILDING the model, so A12 reports that failure and
 * nothing else — no `checkConsistency` verdict is produced at all (measured through
 * `ModelLoader.loadAndCheck`). Every other finding in the same report is therefore the checker's own reading
 * rather than something A12 would have said about this model, and the gap says so instead of leaving a
 * consumer to assume the list is A12's.
 */
private fun List<DeclaredReference>.buildBlocked(): List<CoverageGap> =
    filter { it.role == ReferenceRole.COMPUTED_TARGET && it.target !is Reference.Resolved }
        .map {
            CoverageGap(
                at = it.declaredAt,
                blocked = null,
                reason = "the Kernel cannot build a model whose computed field is missing, so it reports " +
                    "this and stops; any other finding here is this checker's own reading of a model A12 " +
                    "never got far enough to judge",
            )
        }

private val UNIMPLEMENTED_FAMILIES = listOf(
    CoverageGap(
        at = null,
        blocked = null,
        reason = "only the reference families are implemented; every other required family of the pinned " +
            "profile was not examined",
    ),
)
