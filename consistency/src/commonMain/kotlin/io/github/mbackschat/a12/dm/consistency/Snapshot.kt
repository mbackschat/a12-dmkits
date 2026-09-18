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
 * @param families what the check INSIDE this operation reused, cell by cell — [FamilyReuse] says what a
 *     cell is, because it is no longer one family.
 */
data class Reuse(
    val sourcesReused: Int,
    val sourcesAdmitted: Int,
    val preparationReused: Boolean,
    val baselineReportReused: Boolean,
    val families: FamilyReuse,
)

/**
 * What one check answered from an earlier check's cached verdicts, and what it had to redo.
 *
 * **The unit is one CELL, which is a family's verdict about one model — or, for a family the checker asks
 * once per declaration, about one declaration.** So the two numbers sum to the cells a check scheduled and
 * not to the family count: the same model can report a larger denominator as more families move to the finer
 * key, which is the reuse growing rather than the checker doing more work. A cell is answered from the cache
 * only when every fact it read last time still has the same value, so [recomputed] counts the cells an edit
 * could have changed the answer for — which is the pair [L's acceptance](
 * ../docs/PORTABLE-CONSISTENCY-PROPOSAL.md) asks for: reuse of unaffected work AND recomputation of what the
 * edit affects. An always-full-recheck implementation reports `reused = 0`, which is what makes this pair a
 * control rather than a statistic.
 *
 * It counts WORK and claims nothing about time, for the same reason [Reuse] does not.
 */
data class FamilyReuse(val reused: Int, val recomputed: Int) {
    companion object {
        /** No check ran, so there was no family work either way — not "nothing was reused". */
        val NONE = FamilyReuse(reused = 0, recomputed = 0)
    }
}

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
 * different proposition, and it keeps that same property by a different route: a family's footprint is
 * OBSERVED rather than declared, because [TrackedFactBase] records what the family read and a recorded read
 * cannot be too narrow. A declared footprint could be, and its detector only ever proves one narrow — which is
 * why that design was dropped (`PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` "L-b's method was probed").
 *
 * A snapshot is a value. Every operation returns a new one and none writes into the receiver, so a consumer
 * may hold an old snapshot and keep getting the answer it already had.
 */
class ConsistencySnapshot private constructor(
    private val workspace: ConsistencyWorkspace,
    private val entryModelId: String,
    private val prepared: PreparedModel?,
    /**
     * The per-family verdicts the report was built from, for the next check over a nearby model.
     *
     * Held rather than recomputed because a keystroke changes ONE declaration: every family whose recorded
     * facts still hold answers from here instead of walking the model again. The store cannot change an
     * answer — a cell is used only when every fact it read compares equal — so this is an optimization whose
     * correctness does not rest on the snapshot being right about what changed.
     *
     * **It names an internal type in the JVM golden's SYNTHETIC constructor, which is accepted rather than
     * unnoticed.** A private constructor reached from a companion is lowered to a `public synthetic <init>`,
     * so every parameter's type appears in the dump — `CheckCells` is the first `internal` type to do so
     * here. Nothing can call it: javac refuses a synthetic member and Kotlin refuses a private one, and the
     * KLIB golden does not carry it at all. The alternatives are all worse — a mutable field contradicts
     * this class's own value semantics, a public wrapper advertises a name with no meaning, and deriving the
     * cells lazily would re-run the very check they were built by.
     */
    private val cells: CheckCells,
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
            reuseOf(prepared, report, FamilyReuse.NONE),
        )
        // Named as locals so the reuse below is a comparison rather than a claim: an implementation that
        // re-prepared or re-checked to answer a draft would bind different objects here and say so.
        val baseline = report
        val run = when (val drafted = base.draftedWith(at, member, text)) {
            // A refused draft never reaches a check, so there is no family work to report either way — and
            // `NONE` is the honest reading of a path with nothing to count, exactly as the two structural
            // source counts below are.
            is Drafted.Refused -> return DraftResult(
                refusedDraft(baseline, drafted.gap),
                reuseOf(base, baseline, FamilyReuse.NONE),
            )
            // The cells this snapshot holds are what make a keystroke cheap: the draft changes one
            // declaration's text, so every family that never read it answers from the baseline's verdict.
            is Drafted.Applied -> checkRun(drafted.prepared, baseline.modelId, emptyList(), cells, CellRetention.KEPT)
        }
        return DraftResult(attribute(baseline, run.report), reuseOf(base, baseline, run.families))
    }

    /**
     * What answering a draft reused, compared against this snapshot's own members.
     *
     * The two flags are MEASURED — [base] and [baseline] are the objects the answer was actually computed
     * from, so an implementation that re-prepared or rechecked reports `false`. The two source counts are
     * structural rather than measured: a draft has no admission call at all, and saying so is the honest
     * reading of a path that cannot read a source.
     */
    private fun reuseOf(
        base: PreparedModel?,
        baseline: ConsistencyReport,
        families: FamilyReuse,
    ) = Reuse(
        sourcesReused = workspace.admittedSources.size,
        sourcesAdmitted = 0,
        preparationReused = base != null && base === prepared,
        baselineReportReused = baseline === report,
        families = families,
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
            // ONE preparation, feeding both halves. Checking through the workspace instead would prepare the
            // entry a second time and hand back a report built from a fact base this snapshot does not hold
            // — so its cells would describe a different object than the one a draft is applied to. An
            // unknown entry has no fact base, and the workspace owns that envelope.
            val run = prepared?.let {
                checkRun(it, entryModelId, workspace.sourceDiagnostics, CheckCells.NONE, CellRetention.KEPT)
            }
            val reused = workspace.admittedSources.count { (id, admitted) ->
                previous?.workspace?.admittedSources?.get(id) === admitted
            }
            return ConsistencySnapshot(
                workspace = workspace,
                entryModelId = entryModelId,
                prepared = prepared,
                cells = run?.cells ?: CheckCells.NONE,
                report = run?.report ?: workspace.check(entryModelId),
                reuse = Reuse(
                    sourcesReused = reused,
                    sourcesAdmitted = workspace.admittedSources.size - reused,
                    // A new snapshot always re-prepares and re-checks: the source set changed, so the old
                    // fact base describes a model that is no longer the one being asked about.
                    preparationReused = false,
                    baselineReportReused = false,
                    // …and for the same reason it carries no cells IN, so every family ran. Structural, like
                    // the two flags above: this is the check that BUILDS the store rather than one that
                    // reads it. Reuse across an updated source set would need the previous snapshot's cells
                    // revalidated against the new fact base, which is a later slice's question.
                    families = FamilyReuse(reused = 0, recomputed = run?.families?.recomputed ?: 0),
                ),
            )
        }
    }
}

/** Admit [sources], prepare [entryModelId] and check it — see [ConsistencySnapshot]. */
fun snapshotOf(sources: List<ModelSource>, entryModelId: String): ConsistencySnapshot =
    ConsistencySnapshot.of(sources, entryModelId)
