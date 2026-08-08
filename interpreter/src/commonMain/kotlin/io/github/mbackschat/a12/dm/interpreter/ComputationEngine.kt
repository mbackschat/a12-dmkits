package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayContext
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayViews
import io.github.mbackschat.a12.dm.interpreter.eval.PoisonedReadException
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
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule
import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry

/** Complete internal computation envelope. Diagnostics stay detached from [Computed], which remains the
 * legacy report-all value projection. */
internal class ComputationEngineRun(
    val results: List<Computed>,
    val formalErrorsInOperands: List<Message>,
    val computedErrors: Map<Pair<String, List<Int>>, Message>,
    val unsupported: List<UnsupportedRule>,
)

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
    private val clock: EvalClock?,
    private val predefinedTypes: Map<String, PredefinedType>,
    private val customFieldTypes: Map<String, CustomFieldType>,
    private val preparedModel: PreparedEvaluationPlan,
    private val plans: ModelStaticPlans,
    private val checks: AutoChecks,
    private val patterns: PatternRegistry,
    private val cacheObserver: () -> CacheObserver? = { null },
) {

    private val scopes: IterationScopes = plans.scopes

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

    /**
     * Compute ONE computed field — its transitive computed-dependency CLOSURE run in dependency order (so cascade
     * operands aren't read stale/empty), the target's own instances projected out (IF129, graduated from IG74).
     * The single-computation twin of `DmInterpreter.testRule`: "run this field like a function". Behaviour-IDENTICAL
     * to [computeInstances] filtered to [fieldPath] — an unrelated computation cannot feed the target, so pruning to
     * the upstream closure changes nothing but the cost (locked by `ComputeFieldInvariantTest`/`ComputeFieldDiffTest`).
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
     * **side-effect-free**: compute in dependency order, accumulating each result into an [overlay] (the
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
    private fun computeOver(rawDoc: Document, comps: List<ComputationDef>, locale: String): ComputationEngineRun {
        // Computation applies the SAME full-call index-default staging as full validation, after formal
        // ingestion and before its preliminary rules (SPEC-2026-07-23-15; the kernel's inherited
        // `formalePruefung` tail): an eligible clean-empty enum index reads its declaration-owned S-value,
        // so a consuming computation computes a VALUE instead of clearing on the missing operand. The
        // staging is call-local — the caller's document is never mutated, and the staged index cell is not
        // a computed target, so it never reaches an outcome or an applied document.
        val indexDefaults = checks.eligibleIndexDefaults(rawDoc)
        val doc = if (indexDefaults.isEmpty()) rawDoc else rawDoc.withComputed(indexDefaults)
        // A field may be targeted by MULTIPLE computations (a composed base + subtype override — IF87): codegen
        // FLATTENS them, in document order, into one per-field first-match table, so the first SELECTED alternative
        // ends the scan for that cell even when it stores nothing; only an UNSELECTED computation (common
        // precondition false / no guard matched) falls through (the kernel's `CalculationController.handleBerechnetenWert` —
        // "for empty values, do like no calculation was done"). [outcomeByCell] keeps the winner per target cell
        // (insertion-ordered), [decided] marks the cells whose flattened scan has ENDED — every SELECTED
        // alternative ends it, whether or not it stored a value (SPEC-2026-07-26-03).
        val outcomeByCell = LinkedHashMap<Pair<String, List<Int>>, Computed>()
        val decided = HashSet<Pair<String, List<Int>>>()
        val overlay = HashMap<Pair<String, List<Int>>, Val>()
        // A computed field a PRIOR computation CLEARED — dependents must read it as EMPTY (empty-as-0 / skip), NOT
        // the stale STORED (seeded) value the overlay would otherwise fall through to. Without this a dependent's
        // `FieldFilled(dep)` reads the stale seed as filled and computes where the kernel (seeing dep empty) clears
        // — a large slice of the IF76/IF77 CVV class, AND the root of the IF76 gate's over-suppression (a cleared
        // dependency read stale-and-invalid would wrongly trip the gate). KERNEL-FINDINGS §11 (cascade clearing).
        val cleared = HashSet<Pair<String, List<Int>>>()
        val emptyOf: (String) -> Val = { p -> emptyValue(model.kinds[p] ?: Kind.STRING) }
        // A computed NUMBER is STORED padded to its field's minFractionalDigits (IF82) — so a downstream read of
        // a cascade-overlaid value must see that SAME stored form. Without this an integer-valued computed
        // operand (a constant `10`, a whole-number result) reads back as `10` (0 fractional digits) and a min-2
        // field's poison gate wrongly flags it `zahlFalscheAnzahlNachkommastellen` →
        // poisons a downstream computation the kernel computes fine (IF90).
        val minFracOf: (String) -> Int = { p -> model.constraintsOf(p).minFractionalDigits ?: 0 }
        // Cause-blind by contract (SPEC-2026-07-26-04): once invalid, a computed cell contributes only its
        // identity to dependent reads. Never retain the failed payload, exception subtype, message, or producer
        // cause here; every such producer class must collapse to the same poisoned-membership observation.
        val poisoned = HashSet<Pair<String, List<Int>>>()
        // The kernel's PRELIMINARY index invalidation reaches the compute path (SPEC-2026-07-23-15): a
        // DUPLICATED index cell is formally invalid for a reading computation (the `uniqueIndex` prepass),
        // and an EMPTY index cell — unlike an ordinary required-empty field — poisons its reader (the
        // preliminary mandatory rule marks it invalid-for-validation before the calculations run). Both are
        // computed on the STAGED document, so a defaulted cell participates as its S-value instead.
        val dup = checks.duplicateIndexCells(doc)
        val emptyIndexCells = checks.preliminaryEmptyIndexCells(doc)
        val formalResults = FormalResultCache(checks, locale, dup)
        val operands = operandSelection(comps)
        val operandRelevant: Relevance = { path, _ -> path in operands.fields }
        val selectedIndexRelevant: Relevance = { path, _ -> path in operands.indexFields }
        val unsupported = checks.customTypeUnsupported(doc, operandRelevant).toMutableList()
        val formalErrorsInOperands = LinkedHashMap<Pair<String, String>, Message>()
        (
            checks.formalErrors(doc, locale, operandRelevant, formalResults, unsupported) +
                checks.mandatoryErrors(doc, locale, selectedIndexRelevant, formalResults, unsupported) +
                checks.uniquenessMessages(doc, dup, locale, selectedIndexRelevant) +
                checks.overRepetitionMessages(doc, locale, operandRelevant)
        ).forEach { formalErrorsInOperands[it.code to it.errorPath] = it }
        val computedErrors = LinkedHashMap<Pair<String, List<Int>>, Message>()
        // The compute-invariant config, built once for every instance's Interpreter (computeMode; no
        // CustomCondition / relevance seams on the compute path). Only the per-instance `invalid` poison gate
        // varies (it closes over the instance context), so it stays an Interpreter constructor arg.
        val computeConfig = EvalConfig(
            clock,
            model.baseYear,
            model.indexFields,
            timeZone = model.timeZone,
            predefinedTypes = predefinedTypes,
            customFieldTypes = customFieldTypes,
            patternMatches = patterns::matches,
            computeScanState = { cell ->
                when {
                    cell.baseState != CellState.FILLED ->
                        if (cell.path to cell.reps in emptyIndexCells) CellState.INVALID else cell.baseState
                    cell.raw == null -> CellState.EMPTY
                    formalResults.failureAt(cell.path, cell.reps, cell.raw) != null -> CellState.INVALID
                    else -> CellState.FILLED
                }
            },
            computeMode = true,
        )
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
                fun computeAt(ctx: EvalContext) {
                    val targetKey = comp.targetPath to ctx.repsOf(comp.targetPath)
                    if (targetKey in decided) return   // an earlier computation's non-empty outcome already won (IF87)
                    val ectx = if (overlay.isEmpty() && cleared.isEmpty() && poisoned.isEmpty()) ctx
                        else OverlayContext(ctx, overlay, cleared, poisoned, emptyOf, minFracOf, views, comp.targetPath)
                    val interp = Interpreter(ectx, computeConfig,
                        // The compute gate THROWS (IF85): consulting it on a filled formally-invalid operand IS the
                        // kernel's poisoning read (validation's gate stays Boolean → the 3VL exclusion).
                        invalid = { path ->
                            if (operandNotCheckRelevant(path, ectx, formalResults, emptyIndexCells)) throw PoisonedReadException(path)
                            else false
                        })
                    // A read of a formally-invalid cell — an input field, or a POISONED computed dependency — aborts
                    // this instance (IF85, the kernel's CalculationFieldException → "fehlerhaft berechnet"): it skips
                    // the remaining alternatives, produces no value (reported CLEARED under the report-all contract;
                    // the kernel reports it only when input-filled — kernelComputeGranularity projects), and joins
                    // the poison set so ITS dependents' reads poison transitively. The poison is READ-driven: a
                    // dependent that never actually reads the poisoned target (a short-circuit skipped it) computes
                    // normally — there is no static dependency pre-skip.
                    val computed = try {
                        val selection = evalComputation(parsed, interp)
                        val v = selection.value
                        // ERRORED: poison the target + do NOT overlay — a dependent's READ of it must poison, not read
                        // the bad value. A resolved VALUE overlays (dependents read the computed result). Either way
                        // the cell is DECIDED.
                        //
                        // A CLEARED result splits on SELECTION, which is the whole of SPEC-2026-07-26-03: an
                        // alternative that WAS selected but stored nothing (an Unknown result, or a final empty TEXT —
                        // IF123's no-value store decision) still ENDS the flattened per-target scan, so the cell is
                        // decided and stays cleared; only a computation that was never selected (common precondition
                        // false, or no guard matched) falls through, recorded as a tentative clear (dependents read it
                        // EMPTY, IF77) that a later computation for the SAME cell can still win.
                        toComputed(comp, v, targetKey.second, doc, locale).also { attempt ->
                            val c = attempt.result
                            attempt.computedError?.let { computedErrors[targetKey] = it }
                            attempt.formalOperandError?.let {
                                formalErrorsInOperands[it.code to it.errorPath] = it
                            }
                            if (v is Val.DomainFailure) {
                                // No stored value/delta distinguishes a domain failure from an ordinary clear,
                                // but the target is invalid for the rest of this computation cascade.
                                poisoned += targetKey
                                cleared -= targetKey
                                decided += targetKey
                            } else {
                                when (c.outcome) {
                                    ComputeOutcome.ERRORED -> { poisoned += targetKey; cleared -= targetKey; decided += targetKey }
                                    ComputeOutcome.VALUE -> { overlay[targetKey] = v; cleared -= targetKey; decided += targetKey }
                                    ComputeOutcome.CLEARED -> {
                                        cleared += targetKey
                                        if (selection.selected) decided += targetKey
                                    }
                                }
                            }
                        }.result
                    } catch (_: PoisonedReadException) {
                        // The immediate read path is useful only inside expression-order locks. At the computation
                        // boundary even that path is erased: the reader becomes an identity-only poisoned target.
                        poisoned += targetKey; cleared -= targetKey; decided += targetKey
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
                val targetScope = scopes.scopeFromRefs(listOf(comp.targetPath), parentGroup(comp.targetPath))
                val parallelGroups = scopeRefs.flatMap { ancestorGroups(it).filter { g -> g in model.repeatable } }
                    .distinct().filter { it != targetScope && !targetScope.startsWith("$it/") }
                if (parallelGroups.isNotEmpty()) {
                    val joined = (listOf(targetScope) + parallelGroups).distinct()
                    val invalidCoords = joined.associateWith { g -> invalidIndexParentCoords(doc, g) }
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
                            poisoned += targetKey; cleared -= targetKey; decided += targetKey
                            outcomeByCell[targetKey] = Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = targetKey.second)
                        }
                        // A malformed key OUTSIDE every target instance's shared prefix marks nothing, and the
                        // surviving instances must still compute — so only short-circuit when all were marked.
                        if (markedAny && doc.computeContexts(targetScope).all {
                                (comp.targetPath to it.repsOf(comp.targetPath)) in decided
                            }
                        ) continue
                    }
                    for (pctx in scopes.parallelIterationContexts(targetScope, parallelGroups, doc)) {
                        val reps = pctx.repsOf(comp.targetPath)
                        if (UNKNOWN_REP in reps) continue   // no TARGET row for this key — the calc creates none
                        computeAt(pctx)
                    }
                    continue
                }
                for (ctx in doc.computeContexts(scope)) computeAt(ctx)
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
    private fun invalidIndexParentCoords(doc: Document, group: String): Set<List<Int>> {
        val indexField = model.indexFields.firstOrNull { parentGroup(it) == group } ?: return emptySet()
        val parentDepth = pathParts(group).size - 1
        return doc.contextsFor(group)
            .groupBy { it.repsOf(group).take(parentDepth) }
            .filterValues { rows ->
                val values = rows.map { ctx ->
                    ctx.displayValue(indexField)?.let { indexKeyOf(it, ctx.declaredKind(indexField)) }
                }
                values.any { it.isNullOrEmpty() } || values.size != values.distinct().size
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
        return formalResults.failureAt(path, ctx.repsOf(path), ctx) != null
    }

    /**
     * Evaluate a parsed (tiered) computation in one row context, mirroring the kernel's
     * `CodeGenCalculationAlternative` codegen (KERNEL-FINDINGS §2): a present [commonPrecondition] must hold,
     * then the alternatives are tried IN ORDER and the FIRST whose precondition holds supplies the value (a
     * null precondition is unconditional). If the common precondition fails, or no alternative's precondition
     * holds, the field computes nothing ([Val.Unknown] → CLEARED). A precondition "holds" iff it evaluates to
     * three-valued TRUE — an UNKNOWN/FALSE precondition (e.g. over an empty operand) does not select its branch.
     */
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
        }
    }

    private fun evalComputation(parsed: ParsedComputation, interp: Interpreter): Selection {
        if (parsed.commonPrecondition != null && interp.eval(parsed.commonPrecondition) != Tri.TRUE) return Selection.NONE
        for (alt in parsed.alternatives) {
            if (alt.precondition == null || interp.eval(alt.precondition) == Tri.TRUE) {
                return Selection(true, interp.evalExpression(alt.operation))
            }
        }
        return Selection.NONE
    }

    /** One computed-field outcome for [comp] at target-cell [reps] from its evaluated [v]: VALUE (resolved,
     *  empty-as-0 applies) / CLEARED (an Unknown, or a final empty text — IF123; no value) / ERRORED (a
     *  formally-invalid or no-fit result — [Computed.value] retains the ATTEMPTED rendered value, matching
     *  the kernel's store-and-flag, IF34/IF92). A numeric domain failure has no value and therefore retains
     *  the public CLEARED/no-value delta while its target is internally poisoned for dependent reads.
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
            is Val.Bool -> v.v.toString()
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

    /** Render a computed [Val.Range] to the target field's `(format, separator)` (e.g. `01.06.2024-30.06.2024`);
     *  ISO `start/end` fallback when the target declares no range config or an endpoint is malformed. */
    private fun renderRange(v: Val.Range, targetPath: String): String {
        val cfg = model.rangeConfig[targetPath] ?: return "${v.start}/${v.end}"
        val (format, sep) = cfg
        val s = renderDateToFormat(v.start, format)
        val e = renderDateToFormat(v.end, format)
        return if (s != null && e != null) "$s$sep$e" else "${v.start}/${v.end}"
    }

    /** Render a computed scalar to the TARGET field's stored form: a DATE result is reformatted from the
     *  interpreter's ISO to the target's declared date format (the kernel stores a computed date in the field's
     *  format, IF33). STRING and DATE_TIME results pass through (no datetime re-formatter yet). */
    private fun renderScalarValue(iso: String, targetPath: String): String =
        if (model.kinds[targetPath] == Kind.DATE)
            model.dateFormats[targetPath]?.let { renderDateToFormat(iso, it) } ?: iso
        else iso

    private data class OperandSelection(
        val fields: Set<String>,
        val indexFields: Set<String>,
    )

    /**
     * The eager formal-input inventory that precedes computation scheduling. It is intentionally wider than a
     * runtime read trace: all statically referenced non-computed fields are selected; a group reference expands
     * to its contained fields; and index fields join the inventory when target iteration, semantic lookup, or
     * cross-group parallel iteration requires them. Merely reading a sibling field does not select its index.
     */
    private fun operandSelection(comps: List<ComputationDef>): OperandSelection {
        val computedTargets = model.computations.mapTo(HashSet()) { it.targetPath }
        val fields = LinkedHashSet<String>()
        val indexes = LinkedHashSet<String>()

        fun selectIndexesBelow(groups: Collection<String>) {
            model.indexFields.filterTo(indexes) { parentGroup(it) in groups }
        }

        for (comp in comps) {
            val parsed = preparedModel.computation(comp)
            val dependencies = plans.computationDepRefs(parsed)
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

            val scopeRefs = computationScopeRefs(parsed)
            val targetScope = scopes.scopeFromRefs(listOf(comp.targetPath), parentGroup(comp.targetPath))
            val parallelGroups = scopeRefs
                .flatMap { ancestorGroups(it).filter { group -> group in model.repeatable } }
                .distinct()
                .filter { it != targetScope && !targetScope.startsWith("$it/") }
            selectIndexesBelow(parallelGroups)
        }
        fields += indexes
        return OperandSelection(fields, indexes)
    }

    /** Non-starred refs of a whole tiered computation — its iteration SCOPE input: each operation's non-starred
     *  expr refs plus each precondition's + the common precondition's refs (a starred aggregate is evaluated
     *  WITHIN the scope, so it does not drive it — mirrors the single-operation `exprRefs` use). */
    private fun computationScopeRefs(parsed: ParsedComputation): List<String> =
        condRefsOrEmpty(parsed.commonPrecondition) +
            parsed.alternatives.flatMap { condRefsOrEmpty(it.precondition) + exprRefs(it.operation) }

}
