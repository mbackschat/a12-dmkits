package io.github.mbackschat.a12.dm.consistency

/**
 * A finite set of model sources, checked as a closure rather than one file at a time.
 *
 * A DocumentModel that mounts another model cannot be checked alone: its included declarations are real and
 * live in a source this one does not carry, so a single-source check can only report the fact base as
 * incomplete. The workspace is where a caller supplies the rest, and it mirrors the interpreter's
 * `ModelWorkspace` shape on purpose — a finite, caller-supplied, EXACT-ID source set, admitted and frozen at
 * construction, with the entry model named per check — so a consumer of either product meets one concept.
 * It is a checker-owned type all the same, because the published checker carries no interpreter dependency.
 *
 * **Identity is the source's own `header.id`**, never a caller-supplied key: that is what an include's
 * `header.modelReferences[alias].reference` names, so a map keyed by anything else would resolve by
 * coincidence. A repeated id is a diagnostic and the first source wins, so a report never depends on argument order.
 *
 * Construction admits every source; nothing is expanded until [check] names an entry.
 */
class ConsistencyWorkspace private constructor(
    private val admitted: Map<String, Admission.Admitted>,
    private val rejected: List<Diagnostic>,
) {

    /** Every model this workspace can be asked about, in the order the sources were supplied. */
    val modelIds: List<String> get() = admitted.keys.toList()

    /**
     * Check the model with [entryModelId], mounting whichever of the other sources its includes name.
     *
     * An unknown entry is a diagnostic and an INCONCLUSIVE report, not an exception: a caller that mistypes
     * an id needs the same non-throwing envelope as one whose model is invalid.
     */
    fun check(entryModelId: String): ConsistencyReport {
        val entry = admitted[entryModelId]
            ?: return unknownEntry(entryModelId)
        return check(entry, admitted - entryModelId, rejected)
    }

    /**
     * The same workspace with [source] admitted in place of whatever declared its id — every OTHER source's
     * admission is REUSED, which is the point: re-reading them would re-parse text that did not change.
     *
     * Replacing rather than keeping the first is the deliberate difference from [fromSources], where first
     * wins so that a report never depends on argument order. Here the caller is naming one source and saying
     * "this is its new text", which is an instruction rather than an ordering.
     *
     * A source that cannot be admitted CHANGES NOTHING but its diagnostic: with no `header.id` there is no
     * identity, so nothing can be replaced, and dropping whatever stood there would silently discard a model
     * the caller never asked to remove.
     */
    fun with(source: ModelSource): ConsistencyWorkspace = when (val outcome = admit(source)) {
        is Admission.Rejected -> ConsistencyWorkspace(admitted, rejected + outcome.diagnostic)
        is Admission.Admitted -> ConsistencyWorkspace(admitted + (outcome.modelId to outcome), rejected)
    }

    /** The same workspace without the source declaring [modelId]; every other admission is reused. */
    fun without(modelId: String): ConsistencyWorkspace =
        ConsistencyWorkspace(admitted - modelId, rejected)

    /**
     * The admitted sources, by id — for a caller inside this module that has to tell a REUSED parse from a
     * fresh one, which only object identity can answer.
     */
    internal val admittedSources: Map<String, Admission.Admitted> get() = admitted

    /** The diagnostics about the source SET itself, which ride into every report this workspace produces. */
    internal val sourceDiagnostics: List<Diagnostic> get() = rejected

    private fun unknownEntry(entryModelId: String) = ConsistencyReport(
        modelId = null,
        status = ConsistencyStatus.Inconclusive,
        findings = emptyList(),
        diagnostics = rejected + Diagnostic(
            DiagnosticCode.SOURCE_NOT_IN_WORKSPACE,
            modelId = entryModelId,
            explain = "no supplied source declares `header.id` `$entryModelId`; this workspace holds " +
                "${modelIds.ifEmpty { listOf("nothing") }}",
        ),
        checksCompleted = emptySet(),
        gaps = listOf(CoverageGap(null, "the entry model was not supplied, so nothing was checked")),
    )

    companion object {
        /**
         * Admit and freeze [sources]. A source that cannot be admitted is retained as a diagnostic and
         * carried into every report, because a missing provider is exactly what makes a later mount
         * unexpandable — and a caller must be able to see the cause, not only the consequence.
         */
        fun fromSources(sources: List<ModelSource>): ConsistencyWorkspace {
            val admitted = LinkedHashMap<String, Admission.Admitted>()
            val rejected = mutableListOf<Diagnostic>()
            sources.forEach { source ->
                when (val outcome = admit(source)) {
                    is Admission.Rejected -> rejected += outcome.diagnostic
                    // FIRST supplied wins, so the closure a report describes does not depend on argument
                    // order. The duplicate is reported rather than dropped: an include naming that id would
                    // otherwise resolve to a model the caller cannot tell from the one they meant.
                    is Admission.Admitted ->
                        // `putIfAbsent` is a JVM-only default method, so the check is explicit — this module
                        // compiles for Node as well and a JVM-only spelling fails only on the other target.
                        if (outcome.modelId in admitted) {
                            rejected += Diagnostic(
                                DiagnosticCode.SOURCE_IDENTITY_NOT_UNIQUE,
                                modelId = outcome.modelId,
                                explain = "another supplied source already declares `header.id` " +
                                    "`${outcome.modelId}`; this one is not used",
                                diagnosticLabel = source.diagnosticLabel,
                            )
                        } else {
                            admitted[outcome.modelId] = outcome
                        }
                }
            }
            return ConsistencyWorkspace(admitted, rejected)
        }
    }
}
