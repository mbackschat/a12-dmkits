package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.eval.CascadeOverlay
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.WorkLedger
import io.github.mbackschat.a12.dm.interpreter.eval.IndexColumn
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayContext
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayViews
import io.github.mbackschat.a12.dm.interpreter.eval.PoisonedReadException
import io.github.mbackschat.a12.dm.interpreter.eval.RuntimeEvaluationFailure
import io.github.mbackschat.a12.dm.interpreter.eval.CellState
import io.github.mbackschat.a12.dm.interpreter.eval.UNKNOWN_REP
import io.github.mbackschat.a12.dm.interpreter.eval.renderDateToFormat
import io.github.mbackschat.a12.dm.interpreter.eval.storedComputedNumber
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedSubject
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule
import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry

/** Complete internal computation envelope. Diagnostics stay detached from [Computed], which remains the
 * legacy report-all value projection. */
internal class ComputationEngineRun(
    val results: List<Computed>,
    val formalErrorsInOperands: List<Message>,
    val computedErrors: Map<Pair<String, List<Int>>, Message>,
    val unsupported: List<UnsupportedRule>,
) {
    init {
        // The cascade must finish before this boundary; the callback control in
        // ComputationDiagnosticAssemblyLawsTest distinguishes result assembly from an eager refusal.
        if (formalErrorsInOperands.size + computedErrors.size > 1) {
            val byPointer = HashMap<A12PartiallyKnownMultiPointer, Message>()
            for (diagnostic in formalErrorsInOperands.asSequence() + computedErrors.values.asSequence()) {
                val previous = byPointer.put(diagnostic.errorPointer, diagnostic)
                if (previous != null) throw ComputationDiagnosticAssemblyFailure(previous, diagnostic)
            }
        }
    }
}

private class ComputationDiagnosticAssemblyFailure(first: Message, second: Message) :
    IllegalStateException("Duplicate computation diagnostic at ${second.errorPointer}: ${first.code} and ${second.code}")

/**
 * The computation engine — the cascade (overlay / poison / cleared), the parallel join-by-key iteration,
 * and the dependency ordering — a call-invariant collaborator of the I2 eval-state seam (IF117). The
 * per-call cascade state stays local to [computeInstances] (reified as the [OverlayContext] decorator);
 * the poison gate consults the same call-local [FormalResultCache] as validation
 * but THROWS — consulting it on a filled formally-invalid operand IS the kernel's poisoning read (IF85).
 * Allocated once per [DmInterpreter].
 */
internal class ComputationEngine(
    private val model: ModelDef,
    private val clock: () -> EvalClock?,
    private val predefinedTypes: Map<String, PredefinedType>,
    private val customFieldTypes: Map<String, CustomFieldType>,
    private val preparedModel: PreparedEvaluationPlan,
    private val plans: ModelStaticPlans,
    private val checks: AutoChecks,
    private val patterns: PatternRegistry,
    private val cacheObserver: () -> CacheObserver? = { null },
    private val workLedger: () -> WorkLedger? = { null },
) {

    private val scopes: IterationScopes = plans.scopes

    private val computedTargets: Set<String> = model.computations.mapTo(HashSet()) { it.targetPath }

    /** The operand inventory of the WHOLE programme, which every compute call uses, [computeField]'s closure too:
     *  A12 derives it once from every computation, so an unrelated computation still bounds a filter walk
     *  (`adapter.laws.FilterCacheScopeDiffTest`). */
    private val programmeOperands: OperandSelection by lazy { operandSelection(plans.allComputationOrder()) }

    /**
     * How each declaration takes part in its target's first-match table when some declaration of that target
     * JOINS another keyed group (a12-kernel-lean SPEC-2026-10-24-09 and SPEC-2026-10-25-03, KERNEL-FINDINGS KF619).
     * Measured on the dynamic Kernel by `adapter.laws.JoinedTargetDeclarationTableDiffTest`: a partnerless
     * declaration LEADING such a table clears the target at every row whatever any guard says, while one behind a
     * joined lead is passed over unread, so a later joined declaration computes even where its guard holds.
     * Derived from the whole model in document order, because [computeField]'s closure must not change which table
     * a declaration sits in or where.
     */
    private val tableRoles: Map<ComputationDef, TableRole> by lazy {
        buildMap {
            for ((target, declarations) in model.computations.groupBy { it.targetPath }) {
                val joined = declarations.map {
                    joinedSidesOf(preparedModel.computation(it), targetScopeOf(target)).isNotEmpty()
                }
                if (joined.none { it }) continue
                // A ComputationDef compares by value, so a byte-identical repeat shares its first occurrence's
                // role; behind a clearing lead it is never reached, and otherwise the two roles coincide.
                declarations.forEachIndexed { i, declaration ->
                    if (!joined[i] && declaration !in this) {
                        put(declaration, if (i == 0) TableRole.CLEARS else TableRole.PASSED_OVER)
                    }
                }
            }
        }
    }

    /** A declaration's part in a joined target's table; a declaration absent from [tableRoles] is ordinary. */
    private enum class TableRole { ORDINARY, PASSED_OVER, CLEARS }

    /**
     * Every computed-field outcome for the WHOLE model WITH its target cell's repetition indices
     * ([Computed.indices]) — the runtime twin of the kernel's whole-document `compute()`, and the core behind
     * both [compute] (which returns it as the public report) and [applyComputations] (which needs the indices to
     * address the right cell). The per-row identity flows straight onto [Computed] (no separate carrier), which
     * the conformance corpus records (CONFORMANCE-CORPUS-SPEC §7). Delegates to [computeOver] over ALL
     * computations in dependency order.
     */
    fun computeInstances(doc: Document, locale: String): List<Computed> =
        computeRun(doc, locale).results

    /** Whole-model result retaining the explanatory channels consumed by the public API. */
    fun computeRun(doc: Document, locale: String): ComputationEngineRun =
        computeOver(doc, plans.allComputationOrder(), locale)

    fun computeRunWithSupport(doc: Document, locale: String): ComputationEngineRun =
        computeOver(doc, plans.allComputationOrder(), locale, UnsupportedHandling.RETAIN_COMPLETED)

    private enum class UnsupportedHandling { THROW, RETAIN_COMPLETED }

    /**
     * Compute ONE computed field — its transitive computed-dependency CLOSURE run in dependency order (so cascade
     * operands aren't read stale/empty), the target's own instances projected out (IF129, graduated from IG74).
     * The single-computation twin of `DmInterpreter.testRule`: "run this field like a function". Behaviour-IDENTICAL
     * to [computeInstances] filtered to [fieldPath]: an unrelated computation cannot feed the target, and the operand
     * inventory that bounds a filter walk stays the whole programme's, so pruning to the upstream closure changes
     * nothing but the cost (locked by `ComputeFieldInvariantTest`/`ComputeFieldDiffTest`/`FilterCacheScopeDiffTest`).
     * Returns one [Computed] per target-cell instance (a repeated computed field → one per row). A non-computed
     * [fieldPath] has an empty closure → empty list; the public [DmInterpreter.computeField] guards that up front.
     */
    fun computeField(fieldPath: String, doc: Document, locale: String): List<Computed> =
        computeOver(doc, dependencyClosureOf(fieldPath), locale).results.filter { it.path == fieldPath }

    /**
     * The cascade engine over a GIVEN dependency-ordered [comps] list — the shared worker of [computeInstances]
     * (ALL computations) and [computeField] (one field's closure).
     *
     * **Cascade** — a computation may reference ANOTHER computation's target field; the kernel computes the
     * dependency first and the dependent reads its computed value (the CalculationCache). We mirror that
     * **side-effect-free**: compute in dependency order, accumulating each result into a [CascadeOverlay] (the
     * computed values), and evaluate a dependent through an [OverlayContext] that returns those overlaid
     * values for computed-field reads. Without this the dependent read the stored (empty) operand → a wrong
     * value (verified vs the kernel). No doc mutation, so a reused doc never accumulates placements; for a
     * model with no inter-computation dependency the overlay is never consulted (zero behavior change).
     *
     * A computation whose evaluation READS an invalid cell — a formally-invalid input field, an ERRORED
     * computed dependency, or an itself-poisoned one — is POISONED (IF85, the kernel's
     * `CalculationFieldException` → "fehlerhaft berechnet"): no value, reported CLEARED (report-all), and its
     * own dependents' reads poison transitively. Read-driven, so evaluation order/short-circuits decide.
     */
    private fun computeOver(
        rawDoc: Document,
        comps: List<ComputationDef>,
        locale: String,
        unsupportedHandling: UnsupportedHandling = UnsupportedHandling.THROW,
    ): ComputationEngineRun {
        // Computation applies the SAME full-call index-default staging as full validation, after formal
        // ingestion and before its preliminary rules (SPEC-2026-07-23-15; the kernel's inherited
        // `formalePruefung` tail): an eligible clean-empty enum index reads its declaration-owned S-value,
        // so a consuming computation computes a VALUE instead of clearing on the missing operand. The
        // staging is call-local — the caller's document is never mutated, and the staged index cell is not
        // a computed target, so it never reaches an outcome or an applied document.
        //
        // Every computed field's STORED value leaves the document first, because the kernel's cascade never sees
        // one: `CalculationCommand` hands its cache the data without calculated fields ("calculations always
        // produce a new value anyways"), whole-model and not only for the computations this call runs. Without
        // this a stored value filled its group for a `NoGroupFilled`, was counted by `NumberOfFilledFields`, or
        // answered a category read, wherever its own computation cleared or had not run yet
        // (CascadeGroupPresenceDiffTest). Rows stay: a stripped cell keeps its placement, empty.
        val indexDefaults = checks.eligibleIndexDefaults(rawDoc)
        val staged = storedComputedCells(rawDoc) + indexDefaults
        val doc = if (staged.isEmpty()) rawDoc else rawDoc.withComputed(staged, indexDefaults.keys)
        // A field may be targeted by MULTIPLE computations (a composed base + subtype override — IF87): codegen
        // FLATTENS them, in document order, into one per-field first-match table, so the first SELECTED alternative
        // ends the scan for that cell even when it stores nothing; only an UNSELECTED computation (common
        // precondition false / no guard matched) falls through (the kernel's `CalculationController.handleBerechnetenWert` —
        // "for empty values, do like no calculation was done"). [outcomeByCell] keeps the winner per target cell
        // (insertion-ordered), [decided] marks the cells whose flattened scan has ENDED — every SELECTED
        // alternative ends it, whether or not it stored a value (SPEC-2026-07-26-03).
        val outcomeByCell = LinkedHashMap<Pair<String, List<Int>>, Computed>()
        val decided = HashSet<Pair<String, List<Int>>>()
        // The cascade's own state: one entry per cell, carrying overlaid / cleared / poisoned (IF286).
        //
        // `Cleared` records that a computation decided a cell EMPTY. A computed field a PRIOR computation CLEARED
        // must be read as EMPTY (empty-as-0 / skip), never as the stale STORED (seeded) value the caller's
        // document holds — reading the seed was a large slice of the IF76/IF77 CVV class, AND the root of the IF76
        // gate's over-suppression (a cleared dependency read stale-and-invalid wrongly tripped the gate).
        // KERNEL-FINDINGS §11 (cascade clearing). Since the evaluation document is stripped of every stored
        // computed value (above), a cleared cell and a not-yet-computed one read alike; the entry keeps the
        // decision explicit for the readers that consult the cascade before the document.
        //
        // `Poisoned` is cause-blind by contract (SPEC-2026-07-26-04): once invalid, a computed cell contributes
        // only its identity to dependent reads. Never retain the failed payload, exception subtype, message, or
        // producer cause; every such producer class must collapse to the same poisoned observation.
        val cascade = CascadeOverlay(model.repeatable)
        val emptyOf: (String) -> Val = { p -> emptyValue(model.kinds[p] ?: Kind.STRING) }
        // A computed NUMBER is STORED padded to its field's minFractionalDigits (IF82) — so a downstream read of
        // a cascade-overlaid value must see that SAME stored form. Without this an integer-valued computed
        // operand (a constant `10`, a whole-number result) reads back as `10` (0 fractional digits) and a min-2
        // field's poison gate wrongly flags it `zahlFalscheAnzahlNachkommastellen` →
        // poisons a downstream computation the kernel computes fine (IF90).
        val minFracOf: (String) -> Int = { p -> model.constraintsOf(p).minFractionalDigits ?: 0 }
        // The kernel's PRELIMINARY index invalidation reaches the compute path (SPEC-2026-07-23-15): a
        // DUPLICATED index cell is formally invalid for a reading computation (the `uniqueIndex` prepass),
        // and an EMPTY index cell — unlike an ordinary required-empty field — poisons its reader (the
        // preliminary mandatory rule marks it invalid-for-validation before the calculations run). Both are
        // computed on the STAGED document, so a defaulted cell participates as its S-value instead.
        // Once per compute call, on the document every gate below reads (IG127.1).
        val gateNeeded = checks.overRepetitionGateNeeded(doc)
        val valueAdmissions = ValueAdmissionCache(checks, locale, doc, gateNeeded)
        val operands = programmeOperands
        val selectedIndexRelevant: Relevance = { path, _ -> path in operands.indexFields }
        val dup = checks.duplicateIndexCells(doc, valueAdmissions, selectedIndexRelevant)
        val emptyIndexCells = checks.preliminaryEmptyIndexCells(doc)
        val formalResults = FormalResultCache(checks, locale, dup, valueAdmissions = valueAdmissions)
        val invalidOperand: (EvalContext, String) -> Boolean = { reading, path ->
            if (operandNotCheckRelevant(path, reading, formalResults, emptyIndexCells)) throw PoisonedReadException(path)
            else false
        }
        val operandRelevant: Relevance = { path, _ -> path in operands.fields }
        // Over-repetition is the one formal check that speaks about GROUP paths as well as field paths: its
        // second walk reports the over-limit group (serial at the root, row below it). `operandRelevant` holds
        // operand FIELD paths, so testing a group against it was a category error rather than a filter — the row
        // walk could never fire under compute for any model, and the channel was silently short one code on
        // every over-limit operand. Both halves of the scope are measured, not inferred: the kernel reports
        // nothing for an over-limit row in a group no computation reads, and does report an over-limit ANCESTOR
        // two levels above the operand (OverLimitOperandComputeArmDiffTest, 2026-08-31).
        //
        // The set is the COMPLETE ancestry, non-repeatable groups included, mirroring the kernel's own complete
        // walk (KERNEL-FINDINGS KF58). Fixed-group overflows are NOT separately measured on this route,
        // because no input to the public compute surface can reach one: it accepts only a
        // DocumentSnapshot, whose sole public constructors are `readDocument` and `seedDocument`, and A12
        // Document JSON has no shape for a non-repeatable group row above 1 — our own writer refuses that state
        // (A12DocumentJson, DocumentEncodeError), while `seedDocument` instantiates one row per repeatable group.
        // So the extra breadth is deliberate and unobservable here; narrowing it to `model.repeatable` would be
        // the same never-fires predicate this comment exists to record, one kind smaller.
        val operandOverRepetitionRelevant: Relevance =
            { path, _ -> path in operands.fields || path in operands.ancestorGroups }
        val unsupported = checks.customTypeUnsupported(doc, operandRelevant).toMutableList()
        val formalErrorsInOperands = LinkedHashMap<Pair<String, A12PartiallyKnownMultiPointer>, Message>()
        (
            checks.formalErrors(doc, locale, operandRelevant, formalResults, unsupported) +
                checks.mandatoryErrors(doc, locale, selectedIndexRelevant, formalResults, unsupported) +
                checks.uniquenessMessages(doc, dup, locale, selectedIndexRelevant) +
                checks.overRepetitionMessages(doc, locale, operandOverRepetitionRelevant, gateNeeded)
        ).forEach { formalErrorsInOperands[it.code to it.errorPointer] = it }
        val computedErrors = LinkedHashMap<Pair<String, List<Int>>, Message>()
        // The compute-invariant config, built once for every instance's Interpreter (computeMode; no
        // CustomCondition / relevance seams on the compute path). The poison gate uses the reading context
        // supplied by each Interpreter, so its callback is shared across all rows of this compute call.
        // The compute path builds its own config, so it needs the ledger wired here too — counting only the
        // validation config would have left COMPUTE_OVERLAY reporting zero evaluation work.
        val computeConfig = EvalConfig(
            clock(),
            model.baseYear,
            model.indexFields,
            rawStringFields = model.rawStringFields,
            timeZone = model.timeZone,
            predefinedTypes = predefinedTypes,
            customFieldTypes = customFieldTypes,
            patternMatches = patterns::matches,
            indexIdentityAdmitted = formalResults::admitsIdentityAt,
            computeScanState = { cell ->
                when {
                    cell.baseState != CellState.FILLED ->
                        if (cell.path to cell.reps in emptyIndexCells) CellState.INVALID else cell.baseState
                    cell.raw == null -> CellState.EMPTY
                    cell.computed -> CellState.FILLED
                    formalResults.failureAt(cell.path, cell.reps, cell.raw) != null -> CellState.INVALID
                    else -> CellState.FILLED
                }
            },
            computeMode = true,
            filterWalkGroups = { it in operands.ancestorGroups },
            // The empty branch of the poison gate below, mirrored exactly: only a preliminary-invalid index cell
            // can be unavailable when empty, so a starred scan for one is skipped wherever the gate cannot fire.
            emptyMayBeUnavailable = emptyIndexCells.mapTo(HashSet()) { it.first }::contains,
        ).also { it.work = workLedger() }
        // A FILLED operand that is "not check-relevant" — formally invalid — makes a computation over it
        // non-evaluable (IF76, generalized IF78; poison semantics IF85). The gate is the SAME
        // operand-invalidity test full validation uses ([operandNotCheckRelevant] → [FormalResultCache]),
        // but on the compute path a positive test THROWS: the consult IS the kernel's poisoning read. An EMPTY
        // operand — even a required one — stays empty-as-0 (`nichtAngegeben`, not `nichtPruefungsrelevant`).
        for (comp in comps) {
            // The overlay's derived-view cache is FRESH per computation step: within a step only comp.targetPath is
            // written, so a cached index-column / correlation view over any OTHER column is stable and shared across
            // the step's outer rows (IF143); a new step starts clean so a self-write can't read a superseded view.
            val views = OverlayViews(cacheObserver())
            val resolveIndexColumn: (Pair<String, List<Int>>, () -> IndexColumn) -> IndexColumn =
                { (path, scope), build -> views.indexColumn(path, scope, build) }
            try {
                val parsed = preparedModel.computation(comp)
                // A computation iterates where it WRITES (the target's scope), not where it is DECLARED. When neither
                // the operands nor the target has a repeatable ancestor (an all-non-repeatable target), fall back to
                // the TARGET's parent group — NOT `comp.group` (IF89): a computation declared in a separate,
                // uninstantiated container group (a `*_Rules` group) targeting a field in another subtree would
                // otherwise resolve its scope to that empty container and compute nothing, where the kernel computes
                // the single instance at the target (non-repeatable groups are implicitly present).
                val scopeRefs = computationScopeRefs(parsed)
                val scope = scopes.scopeFromRefs(scopeRefs + comp.targetPath, parentGroup(comp.targetPath))

                // Evaluate the computation in one instance context and record its outcome (shared by the plain and
                // the parallel iteration arms).
                val targetScope = targetScopeOf(comp.targetPath)
                val parallelGroups = joinedSidesOf(parsed, targetScope)
                val role = tableRoles[comp] ?: TableRole.ORDINARY

                fun computeAt(ctx: EvalContext) {
                    val targetKey = comp.targetPath to ctx.repsOf(comp.targetPath)
                    if (targetKey in decided) return   // an earlier computation's non-empty outcome already won (IF87)
                    // A target in a row past a declared capacity is never computed: A12 clears it, whatever its
                    // alternatives would give (SPEC-2026-10-04-05). The cell is out of range, so a reader of it
                    // reads an invalid cell, which the poison carries.
                    if (plans.paths.exceededBound(comp.targetPath, targetKey.second) != null) {
                        cascade.poison(targetKey.first, targetKey.second); decided += targetKey
                        outcomeByCell[targetKey] = Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = targetKey.second)
                        return
                    }
                    val ectx = if (cascade.isEmpty) ctx
                        else OverlayContext(ctx, cascade, emptyOf, minFracOf, views, comp.targetPath)
                    val interp = Interpreter(ectx, computeConfig,
                        // The call-owned gate still judges the reading context, including Having rows (IF319).
                        invalid = invalidOperand).sharingIndexColumns(resolveIndexColumn)
                    // A read of a formally-invalid cell — an input field, or a POISONED computed dependency — aborts
                    // this instance (IF85, the kernel's CalculationFieldException → "fehlerhaft berechnet"): it skips
                    // the remaining alternatives, produces no value (reported CLEARED under the report-all contract;
                    // the kernel reports it only when input-filled — kernelComputeGranularity projects), and joins
                    // the poison set so ITS dependents' reads poison transitively. The poison is READ-driven: a
                    // dependent that never actually reads the poisoned target (a short-circuit skipped it) computes
                    // normally — there is no static dependency pre-skip.
                    val computed = try {
                        val selection = evalComputation(parsed, interp, comp.targetPath, role)
                        val v = selection.value
                        // Ordinary target errors poison; the exact reserved custom result retains its readable
                        // attempted value (SPEC-2026-10-10-12). Both decide the target for this call.
                        //
                        // A CLEARED result splits on SELECTION, which is the whole of SPEC-2026-07-26-03: an
                        // alternative that WAS selected but stored nothing (an Unknown result, or a final empty TEXT —
                        // IF123's no-value store decision) still ENDS the flattened per-target scan, so the cell is
                        // decided and stays cleared; only a computation that was never selected (common precondition
                        // false, or no guard matched) falls through, recorded as a tentative clear (dependents read it
                        // EMPTY, IF77) that a later computation for the SAME cell can still win.
                        toComputed(comp, v, targetKey.second, doc, locale).also { attempt ->
                            val c = attempt.result
                            if (!c.value.isNullOrEmpty()) checks.customTypeUnsupportedAt(
                                doc, comp.targetPath, targetKey.second,
                            )?.let { if (it !in unsupported) unsupported += it }
                            if (c.outcome == ComputeOutcome.VALUE || attempt.computedError?.code == CUSTOM_TEST_RANGE) {
                                c.value?.let { valueAdmissions.admitComputedCustomValue(c.path, c.indices, it) }
                            }
                            attempt.computedError?.let { computedErrors[targetKey] = it }
                            attempt.formalOperandError?.let {
                                formalErrorsInOperands[it.code to it.errorPointer] = it
                            }
                            if (v is Val.DomainFailure) {
                                // No stored value/delta distinguishes a domain failure from an ordinary clear,
                                // but the target is invalid for the rest of this computation cascade.
                                cascade.poison(targetKey.first, targetKey.second)
                                decided += targetKey
                            } else {
                                when (c.outcome) {
                                    ComputeOutcome.ERRORED -> {
                                        // testIDNummer remains readable despite its errored result/apply channel (SPEC-2026-10-10-12).
                                        if (attempt.computedError?.code == CUSTOM_TEST_RANGE) {
                                            cascade.overlay(targetKey.first, targetKey.second, storedReading(v, c, doc))
                                        } else cascade.poison(targetKey.first, targetKey.second)
                                        decided += targetKey
                                    }
                                    ComputeOutcome.VALUE -> {
                                        cascade.overlay(targetKey.first, targetKey.second, storedReading(v, c, doc))
                                        decided += targetKey
                                    }
                                    ComputeOutcome.CLEARED -> {
                                        cascade.clear(targetKey.first, targetKey.second)
                                        if (selection.selected) decided += targetKey
                                    }
                                }
                            }
                        }.result
                    } catch (_: PoisonedReadException) {
                        // The immediate read path is useful only inside expression-order locks. At the computation
                        // boundary even that path is erased: the reader becomes an identity-only poisoned target.
                        cascade.poison(targetKey.first, targetKey.second); decided += targetKey
                        Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = targetKey.second)
                    }
                    outcomeByCell[targetKey] = computed   // a decided cell is skipped above → a later computation overwrites only a tentative (unselected) clear
                }

                // PARALLEL ITERATION for computations (IF96): operands referencing OUT-OF-SCOPE sibling repeatable
                // groups join them to the target's scope by the shared INDEX field — the target row for key K reads
                // each sibling's row with the SAME key (join-by-key, never physical position). Two calc-specific
                // rules on top of the rule-side join: (1) an INVALID index in ANY joined group (a duplicated or
                // empty key cell) marks EVERY instance of this computation "incorrectly calculated" — the kernel's
                // CalculationCache.markFieldsUsingInvalidIndexGroupsAsIncorrectlyCalculated, deliberately "more
                // instances than strictly necessary" — poisoned + CLEARED; (2) the calc never CREATES a repetition
                // ("no additional repetitions should be created by calculation"): only join rows where the TARGET's
                // row exists compute — a side-only key is skipped; an unmatched SIDE reads empty (empty-as-0).
                // The join anchors on the TARGET's OWN scope (the calc iterates where it WRITES — IF89): the
                // referenced out-of-scope groups are the joined SIDES, never the iteration anchor ([scope], derived
                // from ALL refs, may resolve to a referenced side — which would collapse every join row onto one
                // target key).
                if (parallelGroups.isNotEmpty()) {
                    // A target below a joined group joins through that group (KERNEL-FINDINGS KF603), so the sides whose
                    // invalid index marks the target are the plan's, not the target's own level.
                    val plan = scopes.prepareParallel(targetScope, parallelGroups)
                    scopes.requireParallelComputationSupport(parsed, plan)
                    val joined = plan.joined
                    val invalidCoords = joined.associateWith { g -> invalidIndexParentCoords(doc, g, formalResults) }
                        .filterValues { it.isNotEmpty() }
                    if (invalidCoords.isNotEmpty()) {
                        var markedAny = false
                        for (ctx in doc.computeContexts(targetScope)) {
                            val targetKey = comp.targetPath to ctx.repsOf(comp.targetPath)
                            if (targetKey in decided) continue
                            if (!invalidCoords.any { (g, coords) ->
                                    markingKey(comp.targetPath, g, targetKey.second) in coords
                                }
                            ) continue
                            markedAny = true
                            cascade.poison(targetKey.first, targetKey.second); decided += targetKey
                            outcomeByCell[targetKey] = Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = targetKey.second)
                        }
                        // A malformed key OUTSIDE every target instance's shared prefix marks nothing, and the
                        // surviving instances must still compute — so only short-circuit when all were marked.
                        if (markedAny && doc.computeContexts(targetScope).all {
                                (comp.targetPath to it.repsOf(comp.targetPath)) in decided
                            }
                        ) continue
                    }
                    for (pctx in scopes.parallelIterationContexts(plan, doc, formalResults::admitsIdentityAt)) {
                        val reps = pctx.repsOf(comp.targetPath)
                        if (UNKNOWN_REP in reps) continue   // no TARGET row for this key — the calc creates none
                        computeAt(pctx)
                    }
                    // The join pairs no row past capacity, yet A12 clears that target row in a joined table too
                    // (`adapter.laws.JoinedTargetDeclarationTableDiffTest`); computeAt's capacity check does it.
                    for (ctx in doc.computeContexts(targetScope)) {
                        if (plans.paths.exceededBound(comp.targetPath, ctx.repsOf(comp.targetPath)) != null) computeAt(ctx)
                    }
                    continue
                }
                for (ctx in doc.computeContexts(scope)) computeAt(ctx)
            } catch (failure: UnsupportedConstructException) {
                when (unsupportedHandling) {
                    UnsupportedHandling.THROW -> throw failure
                    UnsupportedHandling.RETAIN_COMPLETED -> {
                        // Stop this execution without erasing earlier observations (ComputeDocVerbTest).
                        unsupported += UnsupportedRule("<compute>", failure.message.orEmpty(), UnsupportedSubject.ELEMENT)
                        break
                    }
                }
            } finally {
                views.release()
            }
        }
        return ComputationEngineRun(
            results = outcomeByCell.values.toList(),
            formalErrorsInOperands = formalErrorsInOperands.values.toList(),
            computedErrors = computedErrors,
            unsupported = unsupported,
        )
    }

    /**
     * The PARENT coordinates under which [group]'s index is COMPROMISED for the compute parallel join (IF96) —
     * an instantiated row with an EMPTY index cell, or a DUPLICATED index value.
     *
     * Both the detection and the marking are **scoped to the index group's own parent coordinate**: index
     * uniqueness holds within one parent's row set, so the same key value under two different parent rows is
     * not a duplicate, and an invalidity under one parent must not reach instances under another. Pooling every
     * row of [group] across all parents produced both halves of the IF222 defect.
     *
     * Duplication is judged on the canonical [indexKeyOf] form, never raw text ([IF272]) — a Number index field
     * spelled `5` in one row and `5.00` in another compromises the column exactly as `5`/`5` does.
     */
    private fun invalidIndexParentCoords(
        doc: Document,
        group: String,
        formalResults: FormalResultCache,
    ): Set<List<Int>> {
        val indexField = model.indexFields.firstOrNull { parentGroup(it) == group } ?: return emptySet()
        val parentDepth = pathParts(group).size - 1
        return doc.instantiatedContextsFor(group)
            .filter { doc.indexCacheContainsRow(group, it.repsOf(group)) }
            .groupBy { it.repsOf(group).take(parentDepth) }
            .filterValues { rows ->
                val candidates = rows.map { ctx ->
                    val reps = ctx.repsOf(indexField)
                    val raw = ctx.displayValue(indexField)
                    val admitted = !raw.isNullOrEmpty() && formalResults.admitsIdentityAt(indexField, reps, raw)
                    IndexIdentityCandidate(
                        repetitions = reps,
                        key = raw?.let { indexKeyOf(it, ctx.declaredKind(indexField)) }?.takeIf { admitted },
                        compromises = !admitted,
                    )
                }
                resolveIndexIdentity(candidates).compromised
            }
            .keys
    }

    /**
     * The coordinate at which a target instance is tested against [group]'s invalid parent coordinates — the
     * target's own indices truncated to the path prefix it SHARES with [group]'s parent, then padded to that
     * parent's depth (the kernel's `numSharedLevels` + non-repeatable padding).
     *
     * This is what makes the blast radius locus-dependent: for the index group on the target's own path the
     * shared prefix reaches below the sibling level, so sibling rows are distinguished; for an off-path
     * iterated group it stops at the common ancestor, so every instance beneath it is marked — deliberate
     * over-marking the kernel's own comment concedes.
     */
    private fun markingKey(targetPath: String, group: String, targetReps: List<Int>): List<Int> {
        val parentParts = pathParts(group).dropLast(1)
        val sharedLen = pathParts(targetPath).zip(parentParts).takeWhile { (a, b) -> a == b }.size
        return targetReps.take(sharedLen) + List((parentParts.size - sharedLen).coerceAtLeast(0)) { 1 }
    }

    /** Whether an operand is "not check-relevant" for a computation — the SAME test full validation uses
     *  ([FormalResultCache]): the kernel clears a computation over ANY formally-invalid filled operand — every
     *  number formal code (range, fraction-digit, integer-cap, zero-not-allowed, length, leading-zeros, …), a
     *  string length/pattern/enum-domain, a rejected custom type, an over-limit cell, a DUPLICATED index cell —
     *  matching both strategies (differentially confirmed: every formal-error class clears, IF78). An EMPTY
     *  operand → false (empty-as-0 applies, even when required — the kernel's `nichtAngegeben`, IF76) — with
     *  ONE exception: an empty INDEX cell, which the preliminary index mandatory rule invalidates before the
     *  calculations run ([AutoChecks.preliminaryEmptyIndexCells], SPEC-2026-07-23-15). */
    private fun operandNotCheckRelevant(path: String, ctx: EvalContext, formalResults: FormalResultCache,
            emptyIndexCells: Set<Pair<String, List<Int>>>): Boolean {
        if (ctx.displayValue(path) == null) {
            return path to ctx.repsOf(path) in emptyIndexCells   // empty → empty-as-0, unless a preliminary-invalid index
        }
        if (ctx.holdsComputedValue(path)) return false
        return formalResults.failureAt(path, ctx.repsOf(path), ctx) != null
    }

    /**
     * Whether an alternative was SELECTED, and the value it produced. The two are independent: a selected
     * alternative may still yield no storable value. Code generation flattens every computation targeting one
     * field into a single per-field first-match method, and a **selected** alternative ends that scan
     * unconditionally — so SELECTION, not the stored value, is what decides the cell (SPEC-2026-07-26-03).
     */
    private class Selection(val selected: Boolean, val value: Val) {
        companion object {
            /** No alternative was reached — the common precondition was false, or no guard matched. */
            val NONE = Selection(false, Val.Unknown)

            /** The instance hit a [RuntimeEvaluationFailure]: it ends here with the domain failure's outcome — no
             *  value, `berechnungsWertFehler` on the target, a poisoned target for readers (SPEC-2026-10-03-06). */
            val RUNTIME_FAILURE = Selection(true, Val.DomainFailure)

            /** Selected, so the target's scan ends, but no operation runs and nothing is stored. */
            val STORES_NOTHING = Selection(true, Val.Unknown)
        }
    }

    /**
     * Evaluate a parsed (tiered) computation in one row context, mirroring the kernel's
     * `CodeGenCalculationAlternative` codegen (KERNEL-FINDINGS §2): a present [commonPrecondition] must hold,
     * then the alternatives are tried IN ORDER and the FIRST whose precondition holds supplies the value (a
     * null precondition is unconditional). If the common precondition fails, or no alternative's precondition
     * holds, the field computes nothing ([Val.Unknown] → CLEARED). A precondition "holds" iff it evaluates to
     * three-valued TRUE — an UNKNOWN/FALSE precondition (e.g. over an empty operand) does not select its branch.
     */
    private fun evalComputation(
        parsed: ParsedComputation,
        interp: Interpreter,
        targetPath: String,
        role: TableRole,
    ): Selection =
        try {
            selectAlternative(parsed, interp, targetPath, role)
        } catch (_: RuntimeEvaluationFailure) {
            Selection.RUNTIME_FAILURE
        }

    private fun selectAlternative(
        parsed: ParsedComputation,
        interp: Interpreter,
        targetPath: String,
        role: TableRole,
    ): Selection {
        when (role) {
            // Nothing is read in either role, so nothing it would read can poison the target or its readers.
            TableRole.CLEARS -> return Selection.STORES_NOTHING
            TableRole.PASSED_OVER -> return Selection.NONE
            TableRole.ORDINARY -> Unit
        }
        if (parsed.commonPrecondition != null && interp.eval(parsed.commonPrecondition) != Tri.TRUE) return Selection.NONE
        for (alt in parsed.alternatives) {
            if (alt.precondition == null || interp.eval(alt.precondition) == Tri.TRUE) {
                // The target's declared kind types a bare literal operand: a date CONSTANT is written German and
                // has to reach the store canonicalized, or the target's own format cannot render it. The format
                // rides along because a YEARLESS literal needs a reference year only when the target renders one.
                return Selection(true, interp.evalExpression(
                    alt.written, model.temporalClassOf(targetPath), model.dateStoreFormatOf(targetPath)))
            }
        }
        return Selection.NONE
    }

    /** One computed-field outcome for [comp] at target-cell [reps] from its evaluated [v]: VALUE (resolved,
     *  empty-as-0 applies) / CLEARED (an Unknown, or a final empty text — IF123; no value) / ERRORED (a
     *  formally-invalid or no-fit result — [Computed.value] retains the ATTEMPTED rendered value, matching
     *  the kernel's store-and-flag, IF34/IF92). A domain failure — numeric, or a DATE_TIME label with no
     *  instant in the declared zone ([RuntimeEvaluationFailure]) — has no value and therefore retains the public CLEARED/no-value
     *  delta while its target is internally poisoned for dependent reads.
     *  [reps] rides onto [Computed.indices] for the per-row identity (§7). */
    private data class ComputedAttempt(
        val result: Computed,
        val computedError: Message? = null,
        val formalOperandError: Message? = null,
    )

    private fun toComputed(
        comp: ComputationDef,
        v: Val,
        reps: List<Int>,
        doc: Document,
        locale: String,
    ): ComputedAttempt {
        // Render the computed value to the TARGET field's stored form: a Num → canonical decimal; a DATE Txt →
        // the field's date format (IF33; `AddYears(...)` into `dd.MM.yyyy` → `30.06.2026`); a date-RANGE → the
        // field's (format, separator). An Unknown computes nothing → CLEARED.
        val rendered = when (v) {
            // A computed NUMBER stores in the target's form (the kernel's `handleBerechnetenWert` store rule,
            // IF82/IF92): scale-19 pre-round, then — when the natural scale FITS the target's maxFractionalDigits —
            // padded to at least its `minFractionalDigits`, UNCAPPED (10 → "10.00" in a min=2 field; a > 15-digit
            // form stores in full and the formal check below buckets it ERRORED). A value whose natural scale
            // does NOT fit (authorable only via the `errorCodesToSuppress` scale-gate suppression) stores
            // length-bounded to 16 significant digits and is "fehlerhaft berechnet" — ERRORED unconditionally,
            // feeding the same poison set as any other errored computed cell (IF85/IF40).
            is Val.Num -> {
                val stored = storedComputedNumber(v.v, model.constraintsOf(comp.targetPath).minFractionalDigits ?: 0,
                    model.scaleOf(comp.targetPath) ?: Int.MAX_VALUE)
                if (!stored.fits) {
                    val result = Computed(comp.targetPath, ComputeOutcome.ERRORED, stored.rendered, reps)
                    return ComputedAttempt(
                        result,
                        computedError = checkNotNull(
                            checks.computedError(
                                doc, comp.targetPath, reps, stored.rendered, locale, genericWhenValid = true,
                            ),
                        ),
                    )
                }
                stored.rendered
            }
            // A final EMPTY text is NO computed value (IF123) — the kernel's second root-store decision
            // (`CalculationController.handleBerechnetenWert(VkString…)`: `!hasValue()` → "do like no calculation
            // was done"), taken BEFORE the format check (an empty result is never checked, so never ERRORED).
            // Same decision as a bare empty copy; a poisoned/ERRORED result never reaches this arm.
            is Val.Txt -> renderScalarValue(v.v, comp.targetPath)
                .ifEmpty {
                    return ComputedAttempt(Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = reps))
                }
            // A Confirm has no false, and A12 leaves a Confirm target EMPTY for the one computation that can hand it
            // one: a copy of an empty Confirm, which a computation reads as `False` exactly as a condition does.
            // Every other route to `False` is refused statically (`MVK_INVALID_COMPARE_TO_YES`), so whether A12
            // reads the empty source as empty or stores `False` as empty is undecidable on an admitted model;
            // deciding it here keeps the condition reading intact (`adapter.laws.ConfirmComputationOperandDiffTest`).
            is Val.Bool -> if (!v.v && model.kinds[comp.targetPath] == Kind.CONFIRM) {
                return ComputedAttempt(Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = reps))
            } else {
                v.v.toString()
            }
            is Val.Range -> renderRange(v, comp.targetPath)
            is Val.Unknown ->
                return ComputedAttempt(Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = reps))
            is Val.DomainFailure -> {
                val result = Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = reps)
                return ComputedAttempt(
                    result,
                    formalOperandError = checks.computedDomainError(doc, comp.targetPath, reps, locale),
                )
            }
        }
        val error = checks.computedError(doc, comp.targetPath, reps, rendered, locale)
        val result = Computed(
            comp.targetPath,
            if (error == null) ComputeOutcome.VALUE else ComputeOutcome.ERRORED,
            rendered,
            reps,
        )
        return ComputedAttempt(
            result,
            computedError = error,
        )
    }

    /**
     * The transitive computed-dependency CLOSURE of [fieldPath], in dependency order — [fieldPath]'s own
     * computation(s) plus (recursively) every computation whose target [fieldPath] reads, and so on UPSTREAM
     * (never its dependents). The subset [computeField] runs so a cascade operand isn't read stale while unrelated
     * computations are pruned. A non-computed [fieldPath] (no computation targets it) yields an empty list.
     */
    private fun dependencyClosureOf(fieldPath: String): List<ComputationDef> =
        plans.computationClosure(fieldPath)

    /**
     * What a dependent reads from a computed target: a date or a range as the target reads its own stored rendering,
     * so a yearless rendering is re-anchored and a range re-read under the target's runtime format and year
     * interpretation — a FROM range copied into a TO target reads as TO, a yearless `31.12.` + 1 day reads `01.01.` of
     * the anchor year (KERNEL-FINDINGS KF540). A number keeps its own stored-form path (IF90); every other kind
     * renders as computed, so it reads as computed.
     */
    private fun storedReading(v: Val, computed: Computed, doc: Document): Val {
        val rendered = computed.value ?: return v
        return when (model.kinds[computed.path]) {
            Kind.DATE, Kind.DATE_RANGE -> doc.storedValueOf(computed.path, rendered)
            else -> v
        }
    }

    /** Render a computed [Val.Range] to the target field's `(format, separator)` (e.g. `01.06.2024-30.06.2024`);
     *  ISO `start/end` fallback when the target declares no range config or an endpoint is malformed. */
    private fun renderRange(v: Val.Range, targetPath: String): String {
        val cfg = model.rangeConfig[targetPath] ?: return "${v.start}/${v.end}"
        val (format, sep) = cfg
        val s = renderDateToFormat(v.start, format)
        val e = renderDateToFormat(v.end, format)
        return if (s != null && e != null) "$s$sep$e" else "${v.start}/${v.end}"
    }

    /** Render a computed scalar to the TARGET field's stored form: a date result is reformatted from the
     *  interpreter's ISO to the format the target declares, whichever temporal kind declares it (the kernel stores
     *  a computed date in the field's format, IF33, IF397). STRING and DATE_TIME results are stored as
     *  evaluated. */
    private fun renderScalarValue(iso: String, targetPath: String): String =
        model.dateStoreFormatOf(targetPath)?.let { renderDateToFormat(iso, it) } ?: iso

    private data class OperandSelection(
        val fields: Set<String>,
        val indexFields: Set<String>,
        /** Every group above an operand reference, a computed target, or a field a computed target's repeatable
         *  group puts in scope, computed fields included: an over-limit row of such a group is reported even when its
         *  only reference is a computed field, whose own cell is not (SPEC-2026-10-04-05), and its stored rows bound a
         *  computation's filter walk (KERNEL-FINDINGS KF528, its nested-group rows included). */
        val ancestorGroups: Set<String>,
    )

    /** Each computed target's stored cell, mapped to the clear that stages it out of the cascade's document. */
    private fun storedComputedCells(doc: Document): Map<Pair<String, List<Int>>, String?> {
        if (computedTargets.isEmpty()) return emptyMap()
        val cells = HashMap<Pair<String, List<Int>>, String?>()
        for (cell in doc.placementsSnapshot()) {
            if (!cell.isGroup && cell.path in computedTargets && !cell.raw.isNullOrEmpty()) cells[cell.path to cell.reps] = null
        }
        return cells
    }

    /**
     * The eager formal-input inventory that precedes computation scheduling. It is intentionally wider than a
     * runtime read trace: all statically referenced non-computed fields are selected; a group reference expands
     * to its contained fields; and index fields join the inventory when target iteration, semantic lookup, or
     * cross-group parallel iteration requires them. Merely reading a sibling field does not select its index.
     */
    private fun operandSelection(comps: List<ComputationDef>): OperandSelection {
        val fields = LinkedHashSet<String>()
        val indexes = LinkedHashSet<String>()
        val ancestors = HashSet<String>()

        fun selectIndexesBelow(groups: Collection<String>) {
            model.indexFields.filterTo(indexes) { parentGroup(it) in groups }
        }

        for (comp in comps) {
            val parsed = preparedModel.computation(comp)
            val dependencies = plans.computationDepRefs(parsed)
            dependencies.flatMapTo(ancestors, ::ancestorGroups)
            // A12 keeps a calculated field's ancestor groups in the computation's data (CalculationCommand,
            // source-read; measured by FilterCacheScopeDiffTest's target-only row), so their rows count too.
            ancestors += ancestorGroups(comp.targetPath)
            // KF538's authored-guard controls bound this expansion to the inserted implicit guard.
            plans.implicitGuardGroup(comp)
                ?.let { group -> model.kinds.keys.filterTo(fields) { it.startsWith("$group/") && it !in computedTargets } }
            dependencies.filterTo(fields) { it in model.kinds && it !in computedTargets }
            dependencies.filterTo(indexes) { it in model.indexFields }

            val targetIndexGroups = ancestorGroups(comp.targetPath)
                .filter { group -> group in model.repeatable && model.indexFields.any { parentGroup(it) == group } }
            selectIndexesBelow(targetIndexGroups)

            val semanticTargets =
                parsed.commonPrecondition?.let(::semanticIndexTargets).orEmpty() +
                    parsed.alternatives.flatMap { alternative ->
                        alternative.precondition?.let(::semanticIndexTargets).orEmpty() +
                            semanticIndexTargets(alternative.operation)
                    }
            selectIndexesBelow(
                semanticTargets.flatMap(::ancestorGroups).filter { it in model.repeatable },
            )

            selectIndexesBelow(joinedSidesOf(parsed, targetScopeOf(comp.targetPath)))
        }
        fields += indexes
        fields.flatMapTo(ancestors, ::ancestorGroups)
        return OperandSelection(fields, indexes, ancestors)
    }

    private fun targetScopeOf(targetPath: String): String = scopes.scopeFromRefs(listOf(targetPath), parentGroup(targetPath))

    /** The repeatable groups [parsed] joins to [targetScope] by key (IF96): each repeatable group above a non-starred
     *  reference that is neither that scope nor one of its ancestors. A guard's reference counts like an operation's. */
    private fun joinedSidesOf(parsed: ParsedComputation, targetScope: String): List<String> =
        computationScopeRefs(parsed).flatMap { ancestorGroups(it).filter { group -> group in model.repeatable } }
            .distinct().filter { it != targetScope && !targetScope.startsWith("$it/") }

    /** Non-starred refs of a whole tiered computation — its iteration SCOPE input: each operation's non-starred
     *  expr refs plus each precondition's + the common precondition's refs (a starred aggregate is evaluated
     *  WITHIN the scope, so it does not drive it — mirrors the single-operation `exprRefs` use). */
    private fun computationScopeRefs(parsed: ParsedComputation): List<String> =
        condRefsOrEmpty(parsed.commonPrecondition) +
            parsed.alternatives.flatMap { condRefsOrEmpty(it.precondition) + exprRefs(it.operation) }

}
