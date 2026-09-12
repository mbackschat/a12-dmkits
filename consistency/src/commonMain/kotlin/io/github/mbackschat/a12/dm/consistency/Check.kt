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
    val findings = prepared.declaredReferences().mapNotNull { it.target.asFinding(it.declaredAt) }
    return ConsistencyReport(
        modelId = admitted.modelId,
        status = statusOf(findings),
        findings = findings,
        diagnostics = prepared.diagnostics,
        checksCompleted = setOf(CheckFamily.DECLARED_REFERENCES),
        gaps = prepared.gaps() + UNIMPLEMENTED_FAMILIES,
    )
}

/** A declaration the checker could not read was not checked either, so each one is also a coverage gap. */
private fun PreparedModel.gaps(): List<CoverageGap> = diagnostics.map {
    CoverageGap(it.at, null, "this declaration could not be read, so no check ran over it: ${it.explain}")
}

private val UNIMPLEMENTED_FAMILIES = listOf(
    CoverageGap(
        at = null,
        blocked = null,
        reason = "only the declared-reference family is implemented; references inside condition and " +
            "operation text, and every other required family of the pinned profile, were not examined",
    ),
)
