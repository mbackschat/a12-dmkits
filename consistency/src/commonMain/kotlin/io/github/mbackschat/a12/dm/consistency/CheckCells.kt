package io.github.mbackschat.a12.dm.consistency

internal class CheckOutput(
    val contributions: List<ReportFinding>,
    val gaps: List<CoverageGap> = emptyList(),
) {
    val findings: List<Finding> get() = contributions.map { it.finding }
}


/**
 * One producer's output, and the value every fact it read HAD when it read it.
 *
 * **The invalidation rule is the whole of [readValues], and it is sound rather than careful.** A producer is a
 * pure function of the facts it reads through the barrier, so if each of those facts has the same value in
 * another model, the execution is identical — same branches, therefore the same reads, therefore the same
 * output. That argument needs no knowledge of what any family does, which is exactly what a hand-kept
 * footprint could not offer: a transcript cannot be too narrow, because it IS the read
 * (`docs/archived/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` "L-b's design is DECIDED").
 *
 * Cells retain local execution origins, before declaration and model stages select the report.
 * Those stages read current model state beyond a producer's transcript and must run again on reuse.
 */
internal class CheckCell(
    val contributions: List<ReportFinding>,
    val gaps: List<CoverageGap>,
    val readValues: Map<FactRead, Any?>,
) {
    val findings: List<Finding> get() = contributions.map { it.finding }
}


/**
 * The cells one check can hand to the next check over a NEARBY model — an editor's keystroke, in practice.
 *
 * Keyed by [CellKey] — the registration object, which is a singleton in [CHECK_PRODUCERS] and compares by
 * identity, plus the [DeclarationKey] a per-declaration cell answers about. So there is no name to spell two
 * ways, and the one collision a path alone would allow (two declarations at one path, which A12 refuses and
 * this checker reports on) is separated by that key's ordinal.
 *
 * **Absence means work, never a guess.** A producer with no cell, or one whose recorded facts have moved, is
 * simply run — so a family added later is slow rather than wrong, and there is no state in which this store
 * can answer for a producer it has not seen.
 */
internal class CheckCells(private val byKey: Map<CellKey, CheckCell>) {

    /**
     * The cell at [key] when every fact it read still has the value it had, else null.
     *
     * The comparison is by VALUE and not by the fact's identity, which is the difference between a cache and
     * a bug: two models name the same facts and that is precisely when reuse must be decided on their
     * contents.
     *
     * One key at a time, so it opens a [Sweep] of its own and keeps nothing. A caller deciding a whole
     * schedule wants [sweepOver] instead — that is where the cost lives.
     *
     * **No production caller: this is the tests' reference arm, deliberately kept.** `checkRun` decides a
     * schedule through [sweepOver], so every one of this overload's twelve call sites is in `commonTest`,
     * where it is the per-key form a sweep's decisions are compared against. Kept rather than deleted because
     * that comparison is what stops the memo buying speed with a wrong answer — but named here, because an
     * arm with no production caller is otherwise indistinguishable from one whose callers went away.
     */
    fun validIn(model: PreparedModel, key: CellKey): CheckCell? = sweepOver(model).validIn(key)

    /**
     * One pass over a schedule, deciding every cell against [model] and comparing each distinct fact once.
     *
     * **The scope exists because a fact's verdict belongs to the MODEL and the work was being charged per
     * CELL.** Every cell records its own copy of what it read, so a model-wide fact — [FactRead.ModelMember],
     * and above all [FactRead.DeclarationSetShape], whose value is the entire declaration set — was
     * deep-compared once per cell although its answer is one boolean for the whole pass. Cells grow with the
     * model and so does that value, making the pass `O(cells x declarations)`: measured 2026-09-18 at
     * 100/200/400 declarations, a store whose every cell carries the shape decided in 25.8 / 96.6 / 373.3 ms,
     * about 4x per doubling, against 1.3 / 1.6 / 2.7 ms for one [FactRead.DeclarationRow]. That term was 84 %
     * of a keystroke at 400 declarations and pushed the after-edit ratio through its ceiling — which the lane
     * could not see, because a ratio pinned at ONE model size cannot show a curve (`CellValidationSweepTest`,
     * and `docs/PORTABLE-CONSISTENCY-RESULTS.md` for the lane figures).
     *
     * The memo is held HERE and not on the store, because the store is shared and immutable while a pass is
     * neither — a cache on the store would have to be invalidated per model and would publish mutable state
     * to every thread holding a snapshot.
     */
    fun sweepOver(model: PreparedModel): Sweep = Sweep(byKey, model)

    /**
     * One pass's cells, and what deciding them cost.
     *
     * [comparisons] against [examined] is the memo's does-fire evidence rather than a statistic: the
     * invariant is arithmetic, so a count falsifies it deterministically on every carrier, where a timing bar
     * needs calibrating and only fails once the constant already hurts.
     *
     * **Stated precisely, the invariant is one comparison per distinct (fact, recorded instance) PAIR** — not
     * per distinct fact, because [verdicts] overwrites and a store whose cells alternate two instances of one
     * fact would miss on every call and cost what it cost before the memo. That collapses to one per fact
     * exactly when every cell was built against one model, which is true of every store reaching a sweep
     * today: `checkRun` is handed `CheckCells.NONE` when a snapshot is built, and the heterogeneous store a
     * draft produces is discarded rather than carried. So the stronger reading holds by that WIRING and not
     * by anything here — the distinction is locked in `CellValidationSweepTest`, and the slice that would
     * break it is the one `ConsistencySnapshot.of` already names, reuse across an updated source set
     * (found by review, 2026-09-18).
     */
    internal class Sweep(private val byKey: Map<CellKey, CheckCell>, private val model: PreparedModel) {

        /** Each fact this pass has decided, against the value it was recorded with. */
        private val verdicts = mutableMapOf<FactRead, Pair<Any?, Boolean>>()

        /** Recorded reads this pass looked at, memo hits included. */
        var examined: Int = 0
            private set

        /** Those it actually had to compare — one per distinct fact, and the quantity the scope exists for. */
        var comparisons: Int = 0
            private set

        /** The cell at [key] when every fact it read still has the value it had, else null. */
        fun validIn(key: CellKey): CheckCell? {
            val cell = byKey[key] ?: return null
            return cell.takeIf { it.readValues.all { (read, had) -> holds(read, had) } }
        }

        /**
         * Whether [read] still has the [had] it was recorded with — comparing it at most once per pass.
         *
         * **A hit requires IDENTITY on the recorded value, which is what makes the memo sound rather than
         * merely fast.** Identity implies equality, so a hit cannot decide a cell differently than the
         * comparison would have; and a cell holding some other instance simply misses and is compared
         * properly. That is not a rare path being tolerated — every fact [PreparedModel.valueOf] can name is
         * a `by lazy` member, so one model hands out one instance and every cell built against it recorded
         * that same one. The memo is therefore free of any assumption about what cells share.
         */
        private fun holds(read: FactRead, had: Any?): Boolean {
            examined++
            val decided = verdicts[read]
            if (decided != null && decided.first === had) return decided.second
            comparisons++
            val verdict = model.valueOf(read) == had
            verdicts[read] = had to verdict
            return verdict
        }
    }

    companion object {
        /** No cells — a first check, and the value every caller outside the snapshot path passes. */
        val NONE = CheckCells(emptyMap())
    }
}

/**
 * Run [produce] in [key]'s own bucket and key what it produced on the values it read out of [model].
 *
 * The two output channels ride one [CheckOutput] rather than two calls because a cell is one decision: a producer
 * whose findings were reused while its gaps were recomputed would report two halves of two different models.
 * They cannot be swapped by accident — `List<ReportFinding>` and `List<CoverageGap>` are different types.
 */
internal fun TrackedFactBase.cellFor(
    key: CellKey,
    model: PreparedModel,
    produce: () -> CheckOutput,
): CheckCell {
    val output = whileProducing(key, produce)
    return CheckCell(
        gaps = output.gaps,
        contributions = output.contributions,
        readValues = observedWhileProducing(key).associateWith { model.valueOf(it) },
    )
}

/**
 * The value [read] names, read WITHOUT charging it — the store's side of the barrier rather than a family's.
 *
 * It takes the untracked [PreparedModel] on purpose: recording a read here would charge the store's own
 * validation to whichever producer happened to be running, and there is no producer running when a cell is
 * being validated. Nothing reachable from a family can call it, because no family holds a [PreparedModel].
 */
internal fun PreparedModel.valueOf(read: FactRead): Any? = when (read) {
    FactRead.DeclarationSetShape -> declarationSetShape
    // ALL bodies at that path, because the transcript names a path and a model may declare two elements at
    // one — A12 refuses such a model and this checker has to report on it rather than fail, so the path is
    // not a unique key and a value covering only the first body would miss an edit to the second.
    is FactRead.DeclarationContent -> bodiesByPath[read.path].orEmpty()
    // `null` when nothing is declared there any more, which differs from every real row and so fells the
    // cell — the one outcome a removed declaration must produce.
    is FactRead.DeclarationRow -> rowsByPath[read.path]?.getOrNull(read.amongSamePath)
    is FactRead.ModelMember -> when (read.fact) {
        ModelFact.CONDITION_LANGUAGE -> conditionLanguage
        ModelFact.DECLARED_LOCALES -> declaredLocales
        ModelFact.FIELD_REF_BY_SHORT_NAME_ALLOWED -> fieldRefByShortNameAllowed
        ModelFact.HAS_BASE_YEAR -> hasBaseYear
        ModelFact.SUPPORTED_CHARACTERS -> supportedCharacters
        ModelFact.UNREADABLE_LOCALE_CODES -> unreadableLocaleCodes
        ModelFact.DECLARED_TYPE_DEFINITION_IDS -> declaredTypeDefinitionIds
        ModelFact.REPEATABLE_GROUP_SET -> repeatableGroupPathList
    }
}

internal class ExecutedChecks(
    val scheduled: List<ScheduledCell>,
    val cells: Map<CellKey, CheckCell>,
    val reused: Int,
) {
    fun selectedConditions(tracked: TrackedFactBase): List<ConditionFinding> {
        val byOwner = cells.values.flatMap { it.contributions }.filterIsInstance<ConditionFinding>().groupBy { it.owner }
        val declarations = tracked.declarations()
        val owners = declarations.mapTo(mutableSetOf()) { it.key }
        require(byOwner.keys.all { it in owners })
        val ordered = declarations.filter { it.declaredKind == DeclaredKind.COMPUTATION } +
            declarations.filter { it.declaredKind != DeclaredKind.COMPUTATION }
        return ordered.flatMap { declaration ->
            val local = byOwner[declaration.key].orEmpty()
            if (local.isEmpty()) emptyList() else checkNotNull(tracked.conditionExecution(declaration)).selected(local)
        }
    }
}

internal fun executeChecks(
    tracked: TrackedFactBase,
    prepared: PreparedModel,
    carried: CheckCells,
    producers: List<CheckProducer> = CHECK_PRODUCERS,
): ExecutedChecks {
    val scheduled = schedule(tracked, producers)
    val sweep = carried.sweepOver(prepared)
    var reused = 0
    val cells = scheduled.associate { item ->
        val previous = sweep.validIn(item.key)
        if (previous != null) reused++
        item.key to (previous ?: tracked.cellFor(item.key, prepared, item.produce))
    }
    return ExecutedChecks(scheduled, cells, reused)
}
