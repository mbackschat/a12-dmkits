package io.github.mbackschat.a12.dm.consistency

/**
 * What an operation REUSED rather than redid.
 *
 * **The load-bearing fields are measured by object IDENTITY, not assembled from a formula.** [sourcesReused]
 * compares each admitted parse against the one the previous snapshot held, so a re-admission producing an
 * equal-but-new parse counts as no reuse; [preparationReused] and [baselineReportReused] compare the objects
 * an answer was actually computed from against the snapshot's own. A counter derived from the source count
 * would agree with itself however much work was repeated, which is the one way a reuse counter can be worse
 * than none.
 *
 * Two readings are structural rather than measured, and say so here rather than pretending otherwise: making
 * a snapshot always re-prepares and re-checks, so its two flags are `false` by construction; answering a
 * draft never calls admission at all, so its [sourcesAdmitted] is `0` for want of a call site to measure.
 *
 * It counts WORK, and deliberately nothing else. No latency is claimed here or anywhere in this slice: H5
 * owns the after-edit boundary and left it unset, so a number here would be a bar nobody ratified.
 *
 * @param sourcesReused sources whose admitted parse is the very object the previous snapshot held.
 * @param sourcesAdmitted sources this operation had to read, which for an update is the one that changed.
 * @param preparationReused whether the entry model's fact base is the previous snapshot's own — false
 *     whenever the entry or anything it mounts changed, because the fact base then describes the old model.
 * @param baselineReportReused whether the report an answer was attributed against was already computed.
 */
data class Reuse(
    val sourcesReused: Int,
    val sourcesAdmitted: Int,
    val preparationReused: Boolean,
    val baselineReportReused: Boolean,
)

/** A draft's answer, and what answering it reused. */
data class DraftResult(val edit: EditReport, val reuse: Reuse)

/**
 * A frozen analysis of one entry model, updated explicitly rather than redone.
 *
 * **Why this exists, stated as work rather than as speed.** [checkDraft] performs one admission, one
 * preparation and TWO checks per call — the baseline and the drafted fact base — and an editor calls it per
 * keystroke while the baseline side cannot have changed. A snapshot pays for the source parses and the fact
 * base once, so a keystroke costs one check and nothing else. That is a claim about work performed, counted
 * by [Reuse]; no latency is asserted.
 *
 * **Reuse can never change an answer, and that is by construction rather than by care.** Everything reused
 * here is a pure function of inputs that did not change — a source's parse, the entry's fact base, the
 * baseline report — so there is no invalidation rule to get wrong. Per-declaration reuse INSIDE a check is a
 * different proposition and is deliberately not attempted: it would need every family to declare the model
 * footprint its verdict depends on (`PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` "L record").
 *
 * A snapshot is a value. Every operation returns a new one and none writes into the receiver, so a consumer
 * may hold an old snapshot and keep getting the answer it already had.
 */
class ConsistencySnapshot private constructor(
    private val workspace: ConsistencyWorkspace,
    private val entryModelId: String,
    private val prepared: PreparedModel?,
    /** The entry model's report, computed once when this snapshot was made. */
    val report: ConsistencyReport,
    /** What making THIS snapshot reused from the one it came from. */
    val reuse: Reuse,
) {

    /** The same analysis with [source] in place of whatever declared its id, reusing every other parse. */
    fun withSource(source: ModelSource): ConsistencySnapshot = of(workspace.with(source), entryModelId, this)

    /** The same analysis without the source declaring [modelId], reusing every parse that remains. */
    fun withoutSource(modelId: String): ConsistencySnapshot =
        of(workspace.without(modelId), entryModelId, this)

    /**
     * Check DSL text against this snapshot's own fact base — [checkDraft]'s answer without its cost.
     *
     * The draft is applied to the fact base this snapshot already holds and attributed against the report it
     * already computed, so nothing is admitted, nothing is prepared, and the baseline is not rechecked.
     *
     * With no fact base — an entry this workspace does not carry, or one that could not be prepared — the
     * draft is refused with a gap rather than raised, exactly as the stateless call refuses.
     */
    fun draft(at: String, member: String, text: String): DraftResult {
        val base = prepared ?: return DraftResult(
            refusedDraft(report, CoverageGap(
                null,
                "this snapshot has no fact base for `$entryModelId`, so there is nothing for a draft to be " +
                    "checked in",
            )),
            reuseOf(prepared, report),
        )
        // Named as locals so the reuse below is a comparison rather than a claim: an implementation that
        // re-prepared or re-checked to answer a draft would bind different objects here and say so.
        val baseline = report
        val edit = when (val drafted = base.draftedWith(at, member, text)) {
            is Drafted.Refused -> refusedDraft(baseline, drafted.gap)
            is Drafted.Applied ->
                attribute(baseline, checkPrepared(drafted.prepared, baseline.modelId, emptyList()))
        }
        return DraftResult(edit, reuseOf(base, baseline))
    }

    /**
     * What answering a draft reused, compared against this snapshot's own members.
     *
     * The two flags are MEASURED — [base] and [baseline] are the objects the answer was actually computed
     * from, so an implementation that re-prepared or rechecked reports `false`. The two source counts are
     * structural rather than measured: a draft has no admission call at all, and saying so is the honest
     * reading of a path that cannot read a source.
     */
    private fun reuseOf(base: PreparedModel?, baseline: ConsistencyReport) = Reuse(
        sourcesReused = workspace.admittedSources.size,
        sourcesAdmitted = 0,
        preparationReused = base != null && base === prepared,
        baselineReportReused = baseline === report,
    )

    companion object {
        /**
         * Admit [sources], prepare [entryModelId] and check it — the analysis every later operation updates.
         *
         * An unknown entry is an INCONCLUSIVE report naming what the workspace does hold, never an
         * exception: a caller that mistypes an id needs the same envelope as one whose model is invalid.
         */
        fun of(sources: List<ModelSource>, entryModelId: String): ConsistencySnapshot =
            of(ConsistencyWorkspace.fromSources(sources), entryModelId, previous = null)

        private fun of(
            workspace: ConsistencyWorkspace,
            entryModelId: String,
            previous: ConsistencySnapshot?,
        ): ConsistencySnapshot {
            val entry = workspace.admittedSources[entryModelId]
            val prepared = entry?.let {
                prepare(it, workspace.admittedSources - entryModelId)
            }
            val report = workspace.check(entryModelId)
            val reused = workspace.admittedSources.count { (id, admitted) ->
                previous?.workspace?.admittedSources?.get(id) === admitted
            }
            return ConsistencySnapshot(
                workspace = workspace,
                entryModelId = entryModelId,
                prepared = prepared,
                report = report,
                reuse = Reuse(
                    sourcesReused = reused,
                    sourcesAdmitted = workspace.admittedSources.size - reused,
                    // A new snapshot always re-prepares and re-checks: the source set changed, so the old
                    // fact base describes a model that is no longer the one being asked about.
                    preparationReused = false,
                    baselineReportReused = false,
                ),
            )
        }
    }
}

/** Admit [sources], prepare [entryModelId] and check it — see [ConsistencySnapshot]. */
fun snapshotOf(sources: List<ModelSource>, entryModelId: String): ConsistencySnapshot =
    ConsistencySnapshot.of(sources, entryModelId)
