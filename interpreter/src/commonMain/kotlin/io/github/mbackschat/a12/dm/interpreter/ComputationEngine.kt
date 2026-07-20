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
import io.github.mbackschat.a12.dm.interpreter.eval.typeFormatError
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType

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
    private val preparedModel: PreparedModel,
    private val scopes: IterationScopes,
    private val checks: AutoChecks,
) {

    /**
     * Every computed-field outcome for the WHOLE model WITH its target cell's repetition indices
     * ([Computed.indices]) — the runtime twin of the kernel's whole-document `compute()`, and the core behind
     * both [compute] (which returns it as the public report) and [applyComputations] (which needs the indices to
     * address the right cell). The per-row identity flows straight onto [Computed] (no separate carrier), which
     * the conformance corpus records (CONFORMANCE-CORPUS-SPEC §7). Delegates to [computeOver] over ALL
     * computations in dependency order.
     */
    fun computeInstances(doc: Document, locale: String): List<Computed> =
        computeOver(doc, computationsInDependencyOrder(), locale)

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
        computeOver(doc, dependencyClosureOf(fieldPath), locale).filter { it.path == fieldPath }

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
    private fun computeOver(doc: Document, comps: List<ComputationDef>, locale: String): List<Computed> {
        // A field may be targeted by MULTIPLE computations (a composed base + subtype override — IF87): they run in
        // document order and the FIRST NON-EMPTY outcome (a VALUE or a POISON) wins per cell; an empty/clearing one is
        // a no-op and control falls through to the next (the kernel's `CalculationController.handleBerechnetenWert` —
        // "for empty values, do like no calculation was done"). [outcomeByCell] keeps the winner per target cell
        // (insertion-ordered), [decided] marks the cells a non-empty outcome has already claimed.
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
        val poisoned = HashSet<Pair<String, List<Int>>>()
        val formalResults = FormalResultCache(checks, locale, emptySet())
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
            computeScanState = { cell ->
                when {
                    cell.baseState != CellState.FILLED -> cell.baseState
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
            val views = OverlayViews()
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
                        if (operandNotCheckRelevant(path, ectx, formalResults)) throw PoisonedReadException(path)
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
                    val v = evalComputation(parsed, interp)
                    // ERRORED: poison the target + do NOT overlay — a dependent's READ of it must poison, not read
                    // the bad value. A resolved VALUE overlays (dependents read the computed result) and DECIDES the
                    // cell. A CLEARED — an EMPTY (Unknown) result, or a final empty TEXT (IF123: the kernel's
                    // no-value store decision) — is a no-op (IF87): recorded as a tentative clear (dependents read
                    // it EMPTY, IF77) but NOT decided, so a later computation for the SAME cell can still win.
                    toComputed(comp, v, targetKey.second).also { c ->
                        when (c.outcome) {
                            ComputeOutcome.ERRORED -> { poisoned += targetKey; cleared -= targetKey; decided += targetKey }
                            ComputeOutcome.VALUE -> { overlay[targetKey] = v; cleared -= targetKey; decided += targetKey }
                            ComputeOutcome.CLEARED -> cleared += targetKey
                        }
                    }
                } catch (p: PoisonedReadException) {
                    poisoned += targetKey; cleared -= targetKey; decided += targetKey
                    Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = targetKey.second)
                }
                outcomeByCell[targetKey] = computed   // a decided cell is skipped above → a value/poison overwrites a prior tentative clear
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
                if (joined.any { g -> invalidIndexGroup(doc, g) }) {
                    for (ctx in doc.computeContexts(targetScope)) {
                        val targetKey = comp.targetPath to ctx.repsOf(comp.targetPath)
                        if (targetKey in decided) continue
                        poisoned += targetKey; cleared -= targetKey; decided += targetKey
                        outcomeByCell[targetKey] = Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = targetKey.second)
                    }
                    continue
                }
                for (pctx in scopes.parallelIterationContexts(targetScope, parallelGroups, doc)) {
                    val reps = pctx.repsOf(comp.targetPath)
                    if (UNKNOWN_REP in reps) continue   // no TARGET row for this key — the calc creates none
                    computeAt(pctx)
                }
                continue
            }
            for (ctx in doc.computeContexts(scope)) computeAt(ctx)
        }
        return outcomeByCell.values.toList()
    }

    /** Whether [group]'s index is COMPROMISED for the compute parallel join (IF96) — an instantiated row with
     *  an EMPTY index cell, or a DUPLICATED index value. Either marks every instance of a computation joining
     *  over [group] incorrectly calculated (`markFieldsUsingInvalidIndexGroupsAsIncorrectlyCalculated`). */
    private fun invalidIndexGroup(doc: Document, group: String): Boolean {
        val indexField = model.indexFields.firstOrNull { parentGroup(it) == group } ?: return false
        val values = doc.contextsFor(group).map { it.displayValue(indexField) }
        return values.any { it.isNullOrEmpty() } || values.size != values.distinct().size
    }

    /** Whether a FILLED operand is "not check-relevant" for a computation — the SAME test full validation uses
     *  ([FormalResultCache]): the kernel clears a computation over ANY formally-invalid filled operand — every
     *  number formal code (range, fraction-digit, integer-cap, zero-not-allowed, length, leading-zeros, …), a
     *  string length/pattern/enum-domain, a rejected custom type, an over-limit cell — matching both strategies
     *  (differentially confirmed: every formal-error class clears, IF78). An EMPTY operand → false (empty-as-0
     *  applies, even when required — the kernel's `nichtAngegeben`, IF76); duplicate-index is not tracked in
     *  compute (empty [dup]). */
    private fun operandNotCheckRelevant(path: String, ctx: EvalContext, formalResults: FormalResultCache): Boolean {
        if (ctx.displayValue(path) == null) return false   // empty → empty-as-0, not cleared
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
    private fun evalComputation(parsed: ParsedComputation, interp: Interpreter): Val {
        if (parsed.commonPrecondition != null && interp.eval(parsed.commonPrecondition) != Tri.TRUE) return Val.Unknown
        for (alt in parsed.alternatives) {
            if (alt.precondition == null || interp.eval(alt.precondition) == Tri.TRUE) return interp.evalExpression(alt.operation)
        }
        return Val.Unknown
    }

    /** One computed-field outcome for [comp] at target-cell [reps] from its evaluated [v]: VALUE (resolved,
     *  empty-as-0 applies) / CLEARED (an Unknown, or a final empty text — IF123; no value) / ERRORED (a
     *  formally-invalid or no-fit result — [Computed.value] retains the ATTEMPTED rendered value, matching
     *  the kernel's store-and-flag, IF34/IF92; feedback F11a). [reps] rides onto [Computed.indices] for the
     *  per-row identity (§7). */
    private fun toComputed(comp: ComputationDef, v: Val, reps: List<Int>): Computed {
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
                if (!stored.fits) return Computed(comp.targetPath, ComputeOutcome.ERRORED, stored.rendered, reps)
                stored.rendered
            }
            // A final EMPTY text is NO computed value (IF123) — the kernel's second root-store decision
            // (`CalculationController.handleBerechnetenWert(VkString…)`: `!hasValue()` → "do like no calculation
            // was done"), taken BEFORE the format check (an empty result is never checked, so never ERRORED).
            // Same decision as a bare empty copy; a poisoned/ERRORED result never reaches this arm.
            is Val.Txt -> renderScalarValue(v.v, comp.targetPath)
                .ifEmpty { return Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = reps) }
            is Val.Bool -> v.v.toString()
            is Val.Range -> renderRange(v, comp.targetPath)
            is Val.Unknown -> return Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = reps)
        }
        return Computed(comp.targetPath, computedOutcome(comp.targetPath, rendered), rendered, reps)
    }

    /** A computed value's bucket: ERRORED when it is formally INVALID for the target field (e.g. a number out of
     *  the declared range — the kernel returns the value but flags it `getComputedFieldInstancesWithErrors`, IF34);
     *  else VALUE. The value is returned either way. Runs the TYPE-format check only (the kernel's
     *  `checkFieldValueFormatBasic` — a computed value skips the baseline charset/blank checks a stored INPUT
     *  value gets, so a computed string may legally start/end with a blank). */
    private fun computedOutcome(targetPath: String, rendered: String): ComputeOutcome {
        val kind = model.kinds[targetPath] ?: return ComputeOutcome.VALUE
        val invalid = typeFormatError(kind, rendered, model.scaleOf(targetPath), model.dateLikeFormatOf(targetPath),
            model.datePrecisionOf(targetPath), model.rangeSeparatorOf(targetPath), model.constraintsOf(targetPath),
            model.baseYear ?: 2000, model.timeZone) != null
        return if (invalid) ComputeOutcome.ERRORED else ComputeOutcome.VALUE
    }

    /**
     * The model's computations in **dependency order** — a computation whose expression references another
     * computation's target field comes AFTER it (the cascade). A DFS topological sort; a cyclic dependency
     * (which the kernel rejects at codegen) throws. References are taken over ALL field/star paths so a
     * dependency through an aggregate over a computed field is ordered too.
     */
    private fun computationsInDependencyOrder(): List<ComputationDef> =
        orderedFrom(model.computations.map { it.targetPath })

    /**
     * The transitive computed-dependency CLOSURE of [fieldPath], in dependency order — [fieldPath]'s own
     * computation(s) plus (recursively) every computation whose target [fieldPath] reads, and so on UPSTREAM
     * (never its dependents). The subset [computeField] runs so a cascade operand isn't read stale while unrelated
     * computations are pruned. A non-computed [fieldPath] (no computation targets it) yields an empty list.
     */
    private fun dependencyClosureOf(fieldPath: String): List<ComputationDef> =
        orderedFrom(listOf(fieldPath))

    /**
     * The DFS topological sort shared by [computationsInDependencyOrder] (roots = every target) and
     * [dependencyClosureOf] (root = one field). Recurses into the targets a computation reads BEFORE emitting it,
     * so a target is ordered after every target it depends on. A field can be targeted by MULTIPLE computations
     * (a composed base + subtype override — IF87), emitted together in DOCUMENT order ([groupBy] preserves
     * encounter order — the order [computeOver]'s first-non-empty-wins consumes). A cyclic dependency (which the
     * kernel rejects at codegen) throws.
     */
    private fun orderedFrom(roots: List<String>): List<ComputationDef> {
        val byTarget: Map<String, List<ComputationDef>> = model.computations.groupBy { it.targetPath }
        val ordered = mutableListOf<ComputationDef>()
        val done = HashSet<String>()
        val onStack = HashSet<String>()
        fun visit(target: String) {
            if (target in done) return
            if (!onStack.add(target)) {
                throw UnsupportedConstructException("cyclic computation dependency involving $target")
            }
            for (comp in byTarget[target].orEmpty())
                for (ref in computationDepRefs(preparedModel.computation(comp)))
                    if (ref != target && ref in byTarget) visit(ref)
            onStack.remove(target)
            if (done.add(target)) ordered.addAll(byTarget[target].orEmpty())
        }
        roots.forEach { visit(it) }
        return ordered
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

    /** Render a computed scalar to the TARGET field's stored form: a DATE result is reformatted from the
     *  interpreter's ISO to the target's declared date format (the kernel stores a computed date in the field's
     *  format, IF33). STRING and DATE_TIME results pass through (no datetime re-formatter yet). */
    private fun renderScalarValue(iso: String, targetPath: String): String =
        if (model.kinds[targetPath] == Kind.DATE)
            model.dateFormats[targetPath]?.let { renderDateToFormat(iso, it) } ?: iso
        else iso

    /** Non-starred refs of a whole tiered computation — its iteration SCOPE input: each operation's non-starred
     *  expr refs plus each precondition's + the common precondition's refs (a starred aggregate is evaluated
     *  WITHIN the scope, so it does not drive it — mirrors the single-operation `exprRefs` use). */
    private fun computationScopeRefs(parsed: ParsedComputation): List<String> =
        condRefsOrEmpty(parsed.commonPrecondition) +
            parsed.alternatives.flatMap { condRefsOrEmpty(it.precondition) + exprRefs(it.operation) }

    /** All field/star refs of a whole tiered computation — its cascade DEPENDENCY input: each operation's full
     *  refs (incl. starred and `Having`-filter refs, so a dependency through an aggregate over a computed field
     *  is ordered) plus each precondition's + the common precondition's FULL refs ([condDepRefs] — a
     *  precondition's quantifier over a computed field is a dependency too). */
    private fun computationDepRefs(parsed: ParsedComputation): List<String> = (
        (parsed.commonPrecondition?.let { condDepRefs(it) } ?: emptyList()) +
            parsed.alternatives.flatMap {
                (it.precondition?.let { p -> condDepRefs(p) } ?: emptyList()) + referencedFieldPaths(it.operation)
            }
        ).flatMap(::expandGroupDependency)

    /** A bare-group fill predicate depends on every computed target contained by that group, not on the
     *  non-field group path itself. Expansion here serves both whole-model ordering and computeField closure. */
    private fun expandGroupDependency(path: String): List<String> =
        if (path in model.kinds) listOf(path)
        else model.kinds.keys.filter { it.startsWith("$path/") }
}
