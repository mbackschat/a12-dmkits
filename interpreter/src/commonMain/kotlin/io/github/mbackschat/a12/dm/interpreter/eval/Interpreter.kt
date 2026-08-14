package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.parentGroup
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.expansionBindDepth
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldValidationContext
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.MsgType

/** The closed `$` correlation dispatch, exposed internally for deterministic plan/fallback tests. */
internal enum class CorrelationStrategy { KEYED, ORDERED, NESTED }

/**
 * A tree-walking interpreter for the condition AST over an [EvalContext] — pure static common code
 * (no kernel/groovy; the only platform primitive is [Dec]). Reproduces the kernel's runtime semantics:
 * three-valued logic, the TYPE-DRIVEN empty-value handling (number→0, confirm→False, others→not
 * evaluated) gated by the filled-row precondition, operand-list vs starred aggregates, and chronological
 * (lexicographic on ISO) date comparison.
 *
 * A rule FIRES when its error condition evaluates to TRUE.
 *
 * An instance is the **shared eval-state seam** (IF117): one immutable per-row state — `(ctx, config,
 * customErrorField, invalid, outer, outerUnbound)` — that this strategy core and its collaborator
 * extension families (`ExprEval.kt`, `PolarityWalk.kt`, `ValueListQuantEval.kt`, same package) both hold,
 * with no per-row allocation beyond this object. The `internal` members ARE the seam contract — the state
 * reads plus the cross-cluster dispatch; everything else stays private to this class or to a collaborator
 * file.
 */
class Interpreter(
    internal val ctx: EvalContext,
    /** The per-call configuration carried unchanged across the whole validate/compute and every sub-interpreter —
     *  the invariant bundle (clock, baseYear, indexFields, the SPIs, the partial-validation relevance seams,
     *  computeMode). See [EvalConfig]; the two per-row inputs ([customErrorField], [invalid]) stay separate. */
    private val config: EvalConfig = EvalConfig(),
    /** The current rule's error-field pointer (its `errorEntityRelPath` resolved to the row's reps) — the
     *  `errorField` a custom condition's `check` receives. Null outside a rule eval (a computation / `Having`
     *  sub-eval), where `CustomCondition` cannot appear. */
    private val customErrorField: CustomConditionFieldPointer? = null,
    /**
     * Whether a referenced field is **formally invalid** in this context — a present malformed value (a
     * format error) OR a required-but-empty field (a `mandatoryField` error). Such a field resolves to
     * [Val.Unknown], so the kernel's three-valued logic propagates it: a comparison goes UNKNOWN (does not
     * fire), but `… Or <true>` still fires and `… And <true>` stays UNKNOWN — the kernel's operand-level
     * "not check-relevant" behavior, NOT a whole-rule skip. Defaults to "always valid" (the `compute` path,
     * which has no formal-error increment yet). [DmInterpreter] supplies it from the model + context.
     */
    internal val invalid: (String) -> Boolean = { false },
    /** The complete ENCLOSING repetition context for `$` refs: set only while evaluating a `Having`
     *  filter. A marked ref resolves its own named path's coordinate from this environment, not one
     *  scalar index and not the inner filtered row. Null at top level. */
    private val outer: EvalContext? = null,
    /** The overlap-predicate `Having` filter (a plain boolean predicate's rep-list arg) has NO enclosing
     *  iteration to bind `$` to — unlike a quantifier's `Having`, whose star iterates. So here a `$`-marked
     *  ref is UNBOUND; [evalCmp]/[evalPred] reproduce the kernel codegen's trivially-TRUE whole term rather
     *  than binding to [outer] or throwing. Set only by [overlapFilterInterp] (KERNEL-FINDINGS §6, IF72). */
    private val outerUnbound: Boolean = false,
    /**
     * The per-row `RepetitionNotUnique` outcome for THIS rule (IF149) — `(row ctx) → VALUE / OMISSION / null`
     * (null = the row's key is not a duplicate). Read from the call-scoped duplicate cache
     * [io.github.mbackschat.a12.dm.interpreter.RepetitionNotUniqueEvaluator] builds ONCE per pass, so RNU is an
     * ordinary 3VL leaf composed by the boolean walk. Non-null only for a rule whose condition contains an RNU
     * leaf; a `RepetitionNotUnique` predicate reached with this null is reported unsupported (degrade-loud). Null
     * in sub-interpreters ([havingInterp]/[overlapFilterInterp]) — RNU cannot appear inside a `Having`.
     */
    internal val rnuOutcome: ((EvalContext) -> MsgType?)? = null,
) {
    /**
     * Explicit internal rule-evaluation seam for CustomCondition's call-level formal-address provider. The
     * public primary constructor stays ABI-identical; only [ValidationPass] selects this constructor. The
     * provider is lazy, so a pass that never reaches a registered custom leaf never scans formal addresses.
     */
    internal constructor(
        ctx: EvalContext,
        config: EvalConfig,
        customErrorField: CustomConditionFieldPointer,
        invalid: (String) -> Boolean,
        rnuOutcome: ((EvalContext) -> MsgType?)?,
        customFormallyIncorrect: () -> Set<CustomConditionFieldPointer>,
    ) : this(ctx, config, customErrorField, invalid, rnuOutcome = rnuOutcome) {
        this.customFormallyIncorrect = customFormallyIncorrect
    }

    private var customFormallyIncorrect: (() -> Set<CustomConditionFieldPointer>)? = null

    // The config bundle destructured into the fields the walk reads — keeps every internal reference and its
    // direct-field read speed unchanged while the constructor stays small ([EvalConfig]); the config object
    // itself is allocated once per call and reused by [havingInterp]/[overlapFilterInterp], not per row.
    internal val clock = config.clock
    internal val baseYear = config.baseYear
    internal val timeZone = config.timeZone
    internal val indexFields = config.indexFields
    private val predefinedTypes = config.predefinedTypes
    private val customConditions = config.customConditions
    private val customFieldTypes = config.customFieldTypes
    internal val cellRelevant = config.cellRelevant
    internal val starRelevant = config.starRelevant
    internal val starExtentKnown = config.starExtentKnown
    internal val starFullyEnumerated = config.starFullyEnumerated
    private val customRelevant = config.customRelevant
    private val computeScanState = config.computeScanState
    internal val computeMode = config.computeMode

    /** Validation-call-local aggregate results, installed only by [ValidationPass]. A direct low-level
     * [Interpreter] and every compute interpreter deliberately keep this null, so reusing an [EvalConfig] across
     * documents cannot carry values between calls and a mutable computation overlay is never cached. */
    internal var aggregateMemo: MutableMap<AggregateMemoKey, Val>? = null
        private set

    internal fun sharingAggregateMemo(memo: MutableMap<AggregateMemoKey, Val>): Interpreter = apply {
        aggregateMemo = memo
    }

    /** Validation-call-local relevance-scoped [IndexColumn]s, keyed like the document's own cache but owned by
     *  the CALL — a relevance-scoped column may not enter [Document]'s cache, which outlives the call and is
     *  shared by every relevant set of the same document. Installed only by [ValidationPass], and consulted only
     *  when [cellRelevant] is non-null; see [indexColumnOf]. */
    private var indexColumnMemo: ((Pair<String, List<Int>>, () -> IndexColumn) -> IndexColumn)? = null

    internal fun sharingIndexColumns(memo: MutableMap<Pair<String, List<Int>>, IndexColumn>): Interpreter = apply {
        indexColumnMemo = { key, build -> memo.getOrPut(key, build) }
    }

    internal fun sharingIndexColumns(views: OverlayViews): Interpreter = apply {
        indexColumnMemo = { (path, scope), build -> views.indexColumn(path, scope, build) }
    }

    /**
     * The [IndexColumn] of starred [path] **as this call sees it** — the single seam every index-column gate
     * reads, so relevance scoping cannot be applied at some consumers and missed at others ([IF266], the
     * `AtLeastOne` symptom that exposed it).
     *
     * A low-level consumer with neither relevance nor a complete admission gate returns the context's own column
     * unchanged. A validation/computation call rebuilds it from identity-preserving scan cells so registered
     * custom-type rejection participates in the same admission stage as built-in format checks. The result is
     * memoized per (column, star-scope prefix), except for a computation overlay writing that column, whose
     * context declares it unstable through [EvalContext.indexColumnCacheable].
     */
    internal fun indexColumnOf(c: EvalContext, path: String, bindDepth: Int = -1): IndexColumn {
        val relevant = cellRelevant
        val admitted = config.indexIdentityAdmitted
        if (relevant == null && admitted == null) return c.indexColumn(path, bindDepth)
        fun build(): IndexColumn {
            val cells = c.starScanCells(path, bindDepth).map { cell ->
                val value = when {
                    relevant?.invoke(path, cell.reps) == false -> Val.Unknown
                    cell.raw != null && admitted?.invoke(path, cell.reps, cell.raw) == false -> Val.Unknown
                    else -> cell.value
                }
                cell.reps to value
            }.toList()
            return IndexColumn(cells)
        }
        if (!c.indexColumnCacheable(path)) return build()
        val memo = indexColumnMemo ?: return build()
        return memo(path to c.scopePrefixOf(parentGroup(path), bindDepth), ::build)
    }

    /** Formal-aware group state for validation; low-level/compute consumers retain structural Boolean presence. */
    internal fun groupPresence(c: EvalContext, path: String): GroupPresenceState =
        config.groupPresence?.invoke(c, path) ?: GroupPresenceState(c.groupFilled(path), erroneous = false)

    /** The context a node resolves against: the complete enclosing environment for `$`, else [ctx].
     *  Path-specific resolution within that context selects the marked node's named coordinate. */
    internal fun ctxOf(node: Expr): EvalContext = when {
        node is Expr.Field && node.outer -> outerOrThrow()
        node is Expr.Star && node.outer -> outerOrThrow()
        node is Expr.EntitySpec && entitySourceOuter(node.source) -> outerOrThrow()
        node is Expr.EnumCategory && node.outer -> outerOrThrow()
        node is Expr.SemanticIndex && node.outer -> outerOrThrow()
        else -> ctx
    }

    /** The independently-correlatable key operand of a field-keyed semantic index. */
    internal fun indexKeyCtx(node: Expr.SemanticIndex): EvalContext =
        if (node.indexFieldOuter) outerOrThrow() else ctx

    /** One semantic-index cell read through the call's complete admission boundary. */
    internal fun indexedCell(c: EvalContext, targetPath: String, indexField: String, indexValue: String): Val? =
        c.indexedCell(targetPath, indexField, indexValue, config.indexIdentityAdmitted)

    /** Narrow accessor for semantic-row selection; keeps the rest of [EvalConfig] encapsulated. */
    internal fun indexIdentityAdmitted(): ((String, List<Int>, String) -> Boolean)? =
        config.indexIdentityAdmitted

    /** Whether a `$`-marked node is UNBOUND in this context — an overlap-predicate `Having` ([outerUnbound]) has
     *  no enclosing iteration; its complete comparison/predicate becomes trivially TRUE (not [outer], not a throw). */
    internal fun outerUnknown(node: Expr): Boolean = outerUnbound && when (node) {
        is Expr.Field -> node.outer
        is Expr.Star -> node.outer
        is Expr.EntitySpec -> entitySourceOuter(node.source) || entityIndexOuter(node.semanticIndex)
        is Expr.EnumCategory -> node.outer
        is Expr.SemanticIndex -> node.outer || node.indexFieldOuter
        else -> false
    }

    private fun outerOrThrow(): EvalContext =
        outer ?: throw UnsupportedConstructException("a \$ outer-correlation reference is only valid inside a Having filter")

    /** Whether [e]'s expression tree references a `$` outer-correlation — used ONLY in [outerUnbound] mode (an
     *  overlap-predicate `Having`) to detect a term the codegen cannot compile (no enclosing iteration to bind
     *  `$` to), which is therefore trivially satisfied. */
    private fun hasUnboundOuter(e: Expr): Boolean = when (e) {
        is Expr.Field -> e.outer
        is Expr.Star -> e.outer
        is Expr.EntitySpec -> entitySourceOuter(e.source) || entityIndexOuter(e.semanticIndex)
        is Expr.EnumCategory -> e.outer
        is Expr.SemanticIndex -> e.outer || e.indexFieldOuter
        is Expr.Func -> e.args.any { hasUnboundOuter(it) }
        is Expr.Arith -> hasUnboundOuter(e.left) || hasUnboundOuter(e.right)
        else -> false
    }

    private fun entitySourceOuter(source: Expr.EntitySource): Boolean = when (source) {
        is Expr.Field -> source.outer
        is Expr.Star -> source.outer
    }

    private fun entityIndexOuter(index: Expr.EntityIndex?): Boolean =
        index is Expr.EntityIndex.Field && index.outer

    /** A sub-interpreter for a `Having` candidate [rc], carrying the SAME config and capturing the
     *  enclosing iteration's complete [ctx] environment for path-specific `$` resolution. */
    internal fun havingInterp(rc: EvalContext): Interpreter =
        Interpreter(rc, config, customErrorField, invalid, outer = ctx).also {
            it.aggregateMemo = aggregateMemo
            it.indexColumnMemo = indexColumnMemo
        }

    /** A sub-interpreter for the overlap predicates' `Having` filter row [rc] — like [havingInterp] but with NO
     *  outer binding ([outerUnbound]): a plain boolean predicate's rep-list arg has no enclosing iteration, so
     *  the codegen makes a `$`-bearing comparison/predicate trivially TRUE (KERNEL-FINDINGS §6). */
    private fun overlapFilterInterp(rc: EvalContext): Interpreter =
        Interpreter(rc, config, customErrorField, invalid, outer = null, outerUnbound = true)
            .also {
                it.aggregateMemo = aggregateMemo
                it.indexColumnMemo = indexColumnMemo
            }

    fun fires(cond: Cond): Boolean = eval(cond) == Tri.TRUE

    /**
     * Evaluate a value expression (a computation's RHS) to a typed [Val] — NOT gated by the row's
     * `evaluated` flag, since a computation computes even on an otherwise-empty row (an empty numeric
     * operand reads as 0, so `[BaseFee]` over no base fee yields 0, not nothing).
     */
    fun evalExpression(expr: Expr): Val = evalExpr(expr)

    fun eval(cond: Cond): Tri {
        if (!gated(cond)) return Tri.UNKNOWN
        return evalCond(cond)
    }

    /**
     * The polarity [MsgType] of a fired rule, or null when it does not fire — VALUE vs OMISSION. Uses the
     * [evalTypedLazy] single walk (polarity computed only on TRUE nodes), measured faster than the full typed
     * walk in BOTH the realistic low-firing and the error-heavy high-firing regimes (INTERPRETER-PERFORMANCE.md).
     */
    fun firedType(cond: Cond): MsgType? {
        if (!gated(cond)) return null
        val r = evalTypedLazy(cond)
        return if (r.truth == Tri.TRUE) r.type else null
    }

    /** Partial-validation mode (the caller supplied a relevant set → [cellRelevant] is non-null). In partial the
     *  row-content gate is REPLACED by the upstream relevance gate: [DmInterpreter.rowOutcomes] only reaches an
     *  eval when the rule's error field is RELEVANT at the levels its iteration bound (IF268), and the kernel
     *  checks every relevant instance —
     *  firing a comparison's empty-as-0 or an empty-identity aggregate even on a non-content-bearing (empty /
     *  phantom) relevant row (kernel-probed: `MinValue` over `Lines* then Qty` `< 5` on a `/Cart`-relevant empty
     *  document, and `[Note] < 5` on a relevant empty `Note`, both FIRE in partial though neither fires in full). So
     *  the content gate must NOT suppress here; the IF42 star-relevance gate still bounds aggregates (IF54). */
    private val partial: Boolean = cellRelevant != null

    /**
     * Whether the row is evaluated at all. In FULL validation: a content-bearing row always is; an entirely-empty
     * row is evaluated only when the condition CAN fire empty ([canFireEmpty]) — so the negative presence
     * conditions (NoFieldFilled, NotAllFieldsFilled, FieldNotFilled, …) fire on a blank instance, while a
     * comparison's empty-as-0 stays suppressed (the kernel's structural "can fire empty" gate, not a data check).
     * In [partial] validation the gate is bypassed — the upstream relevance gate has already decided (see [partial]).
     */
    private fun gated(cond: Cond): Boolean = partial || ctx.evaluated || canFireEmpty(cond)

    /** Structural: would [cond] fire on an all-empty row? A comparison/pattern can't (empty-as-0 is
     *  suppressed; an empty string/pattern leaf is false); a presence predicate fires iff it is TRUE at a
     *  filled-count of 0; And/Or recurse. (Not is unreachable from the parser but modelled for totality.) */
    private fun canFireEmpty(cond: Cond): Boolean = when (cond) {
        is Cond.Cmp -> false
        is Cond.Pattern -> false
        is Cond.Tolerance -> false   // |0 − 0| = 0 is never > N (the band is ≥ 1) — an all-empty row can't fire
        is Cond.ValueListQuant -> cond.op == ValueListQuantOp.NO   // "no field in list" is TRUE when all are empty
        is Cond.And -> canFireEmpty(cond.left) && canFireEmpty(cond.right)
        is Cond.Or -> canFireEmpty(cond.left) || canFireEmpty(cond.right)
        is Cond.Not -> !canFireEmpty(cond.c)
        is Cond.Pred -> predFiresOnEmpty(cond)
        is Cond.Custom -> true    // hidden dependencies are callback-owned; the leaf is structurally empty-eligible
    }

    /** Whether a presence predicate is TRUE when every operand is known empty (t = 0, f = n, u = 0). */
    private fun predFiresOnEmpty(p: Cond.Pred): Boolean = when (val op = PredicateOp.byName(p.name)) {
        null, PredicateOp.FIELD_FILLED, PredicateOp.GROUP_FILLED,
        PredicateOp.FIELD_VALUE_IN_LIST, PredicateOp.FIELD_VALUE_NOT_IN_LIST,
        PredicateOp.DATE_RANGES_OVERLAP, PredicateOp.ATLEAST_ONE_DATE_RANGE_OVERLAPS,
        PredicateOp.FIELD_VALUES_NOT_UNIQUE, PredicateOp.REPETITION_NOT_UNIQUE,
        PredicateOp.VALID -> false   // needs present ranges / ≥2 present equal values / a real present date / a present value
        PredicateOp.FIELD_NOT_FILLED, PredicateOp.GROUP_NOT_FILLED -> true
        // Invalid(Date(...)) (1 arg) fires on an empty row — every part unspecified IS invalid (kernel-probed,
        // OMISSION); Invalid(field, "Type") (2 args) does NOT — an empty field is "not specified", not evaluated.
        PredicateOp.INVALID -> p.args.size == 1
        else -> quantifierFires(op, t = 0, f = p.args.size, n = p.args.size)   // all-empty: every operand FALSE
    }

    /**
     * The instantiated fill tally of a starred INDEX-field operand, index-aware: a mandatory-EMPTY/absent cell and
     * a DUPLICATED-value cell are NOT-CHECK-RELEVANT (the kernel's `isWertAngegeben` → UNKNOWN), so they land in
     * NEITHER `t` nor `f` (the [FillScope.hasUnknownInst] bucket) exactly like a malformed cell — never as an empty
     * (`f`) or present (`t`) cell. This drives the fill quantifiers over an index field: `AtLeastOneFieldFilled`
     * stops firing when every cell is not-check-relevant (a fully-duplicated index), `NoFieldFilled` stops firing
     * when a cell is not-check-relevant (`f != n`). The DECLARED-range flags are taken from the plain [fillScope]
     * (the un-instantiated tail dominates them). `IndexAggregateDiffTest`.
     */
    private fun indexStarFillScope(c: EvalContext, path: String): FillScope {
        val declared = c.fillScope(path, isGroup = false)
        // empty → mandatory-empty, malformed, duplicated → uniqueIndex: all not-check-relevant, in neither t nor f
        val col = indexColumnOf(c, path)
        return FillScope(col.cleanFilled, 0, col.cells.size, col.anyNotCheckRelevant,
            declared.allFilledDeclared, declared.anyEmptyDeclared)
    }

    /**
     * `left DiffersWithToleranceRangeN right` — fires iff `|left − right| > [Cond.Tolerance.tolerance]` (the
     * band is STRICT). Numbers only; an empty operand resolves to `Val.Num(0)` (empty-as-0, like any
     * comparison); a malformed / non-numeric operand → Unknown → the rule is suppressed. Like the kernel's
     * `vergleiche`, each operand is independently normalized to scale 19 before the difference is taken.
     */
    internal fun evalTolerance(c: Cond.Tolerance): Tri {
        val a = (evalCmpOperand(c.left, c.right) as? Val.Num)?.v ?: return Tri.UNKNOWN
        val b = (evalCmpOperand(c.right, c.left) as? Val.Num)?.v ?: return Tri.UNKNOWN
        return Tri.of(ToleranceOps.differs(a, b, c.tolerance))
    }

    private fun evalCond(c: Cond): Tri = when (c) {
        is Cond.Cmp -> evalCmp(c)
        is Cond.Tolerance -> evalTolerance(c)
        is Cond.ValueListQuant -> evalValueListQuant(c)
        // COMPUTE mode short-circuits like the kernel's generated `isKnownAndTrue` collapse (IF85): an And
        // whose LEFT is not-TRUE (FALSE or UNKNOWN) never evaluates the right — so a poisoning read there
        // does not happen; an Or with a TRUE left likewise. Validation keeps eager Kleene (its UNKNOWN∧FALSE
        // = FALSE distinction is observable in polarity/keep-laws; no poison channel exists there).
        is Cond.And ->
            if (computeMode) evalCond(c.left).let { l -> if (l != Tri.TRUE) l else evalCond(c.right) }
            else Kleene.and(evalCond(c.left), evalCond(c.right))
        is Cond.Or ->
            if (computeMode) evalCond(c.left).let { l -> if (l == Tri.TRUE) Tri.TRUE else Kleene.or(l, evalCond(c.right)) }
            else Kleene.or(evalCond(c.left), evalCond(c.right))
        is Cond.Not -> Kleene.not(evalCond(c.c))
        is Cond.Pred -> evalPred(c)
        is Cond.Pattern -> evalPattern(c)
        is Cond.Custom -> evalCustom(c)
    }

    /**
     * `CustomCondition <name>` — delegate to the consumer-supplied [CustomCondition] (the kernel's
     * `ICustomCondition` SPI). Fires iff `check` returns true; an **unregistered** name → the rule is reported
     * unsupported (fail-fast). Passes the document data view + the rule's exact error field; [customRelevant] (null in
     * full validation, the relevant set in partial — IF38) and the complete externally addressed
     * `formallyIncorrect` set produced by that validation's formal pass, mirroring the kernel's call. The two
     * channels stay distinct: a non-relevant field is not invented as a formal error. The callback owns its
     * hidden dependency checks; this evaluator does not pre-suppress it merely because the set is non-empty.
     */
    internal fun evalCustom(c: Cond.Custom): Tri {
        val condition = customConditions[c.name]
            ?: throw UnsupportedConstructException("unregistered custom condition \"${c.name}\" — register it in the custom-condition registry")
        val errorField = customErrorField
            ?: throw UnsupportedConstructException("custom condition \"${c.name}\" needs the rule's error field — unavailable in this context")
        val data = ctx.customConditionData()
        // The model-aware DmInterpreter path supplies the exact call-level formal set. Retain the former
        // best-effort behavior for direct low-level Interpreter users, whose context has no ModelDef/formal pass.
        val formallyIncorrect = customFormallyIncorrect?.invoke()
            ?: data.fields().filter { invalid(it.path) }.toSet()
        return Tri.of(condition.check(data, relevant = customRelevant, formallyIncorrect = formallyIncorrect, errorField = errorField))
    }

    /** The current row's `RepetitionNotUnique` outcome (VALUE / OMISSION / null=not-a-duplicate) — read from the
     *  per-rule [rnuOutcome] cache seam. A `RepetitionNotUnique` leaf reached with no cache wired (outside a
     *  rule-level position, or in a rule the cache builder declined) degrades LOUD (reported unsupported). */
    internal fun rnuOutcomeAt(): MsgType? {
        val f = rnuOutcome ?: throw UnsupportedConstructException(
            "RepetitionNotUnique is only evaluable as a rule-level leaf")
        return f(ctx)
    }

    internal fun evalPred(p: Cond.Pred): Tri {
        // Overlap-predicate Having: a predicate touching an unbound `$` is trivially TRUE (see [evalCmp]).
        if (outerUnbound && p.args.any { hasUnboundOuter(it) }) return Tri.TRUE
        val op = PredicateOp.byName(p.name) ?: throw UnsupportedConstructException("unsupported predicate ${p.name}")
        return when (op) {
            PredicateOp.FIELD_FILLED -> presenceTri(p.args.single())
            PredicateOp.FIELD_NOT_FILLED -> Kleene.not(presenceTri(p.args.single()))
            PredicateOp.GROUP_FILLED -> groupPresence(ctxOf(p.args.single()), pathOf(p.args.single())).let {
                if (it.relevance == GroupRelevance.NONE) Tri.UNKNOWN else Tri.of(it.filled)
            }
            PredicateOp.GROUP_NOT_FILLED -> groupPresence(ctxOf(p.args.single()), pathOf(p.args.single())).let {
                when {
                    it.erroneous || it.relevance == GroupRelevance.NONE -> Tri.UNKNOWN
                    it.filled -> Tri.FALSE
                    it.relevance == GroupRelevance.FULL -> Tri.TRUE
                    else -> Tri.UNKNOWN
                }
            }
            PredicateOp.FIELD_VALUE_IN_LIST -> valueInList(p.args)
            PredicateOp.FIELD_VALUE_NOT_IN_LIST -> Kleene.not(valueInList(p.args))
            PredicateOp.DATE_RANGES_OVERLAP -> overlapAny(p.args).truth
            PredicateOp.ATLEAST_ONE_DATE_RANGE_OVERLAPS -> atLeastOneOverlap(p.args).truth
            PredicateOp.FIELD_VALUES_NOT_UNIQUE -> fieldValuesNotUnique(p.args).truth
            // An ordinary per-row 3VL leaf (IF149): the row's key is a duplicate (TRUE) or not (FALSE), read from
            // the call-scoped duplicate cache. The polarity (VALUE/OMISSION) rides [rnuOutcome] and is typed in
            // [predType]; here only truth matters. Degrade-loud if the leaf wasn't wired (see [rnuOutcomeAt]).
            PredicateOp.REPETITION_NOT_UNIQUE -> Tri.of(rnuOutcomeAt() != null)
            // Valid/Invalid(Date(...)): 3VL over the construction's status. A malformed part makes the whole
            // non-evaluable (UNKNOWN — both go quiet, the formal error fires); else Valid iff real-and-all-present,
            // Invalid its strong-Kleene TRUTH complement (unreal OR an empty part). Full verdicts are not
            // interchangeable: polarity still distinguishes incomplete OMISSION from unreal-present VALUE.
            // Two overloads share the Valid/Invalid keyword, disambiguated by arity: the date-construct form
            // `Valid(Date(...))` (1 arg) and the predefined-type form `Valid(field, "TypeName")` (2 args).
            PredicateOp.VALID -> if (p.args.size == 2) predefinedTypeValidity(p.args, expectValid = true)
                else when (dateStatusOf(p.args.single())) {
                    DateStatus.REAL -> Tri.TRUE
                    DateStatus.UNREAL_PRESENT, DateStatus.HAS_EMPTY -> Tri.FALSE
                    DateStatus.MALFORMED -> Tri.UNKNOWN
                }
            PredicateOp.INVALID -> if (p.args.size == 2) predefinedTypeValidity(p.args, expectValid = false)
                else when (dateStatusOf(p.args.single())) {
                    DateStatus.REAL -> Tri.FALSE
                    DateStatus.UNREAL_PRESENT, DateStatus.HAS_EMPTY -> Tri.TRUE
                    DateStatus.MALFORMED -> Tri.UNKNOWN
                }
            // List quantifiers over the (TRUE, FALSE) operand tally (row-evaluated gate is upstream in eval).
            // COMPUTE mode replaces the tally with the kernel's ordered SCAN so a read is order-observable and a
            // later invalid operand after the deciding one is never read (IF85): the FIELD-fill family scans cells
            // ([computeFillQuantifier]); the GROUP-fill family scans its common entity list ([computeGroupFillQuantifier],
            // IF219) — a FIELD operand READS its cell (an invalid one poisons), a GROUP operand is structural
            // presence (never poison), each yielded lazily so the per-operator stop ends the scan.
            PredicateOp.ALL_FIELDS_FILLED, PredicateOp.NO_FIELD_FILLED, PredicateOp.AT_LEAST_ONE_FIELD_FILLED,
            PredicateOp.MORE_THAN_ONE_FIELD_FILLED, PredicateOp.NOT_ALL_FIELDS_FILLED,
            PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED, PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED,
            PredicateOp.ALL_GROUPS_FILLED, PredicateOp.NO_GROUP_FILLED, PredicateOp.AT_LEAST_ONE_GROUP_FILLED,
            PredicateOp.NOT_ALL_GROUPS_FILLED, PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED -> when {
                computeMode && !op.countsGroups -> computeFillQuantifier(op, p.args)
                computeMode && op.countsGroups -> computeGroupFillQuantifier(op, p.args)
                !op.countsGroups && hasFillScopeOperand(p.args) -> Tri.of(fillScopeFires(op, fillScopeInfo(p.args)))
                else -> {
                    val (t, f, n) = quantCounts(op, p.args)
                    Tri.of(quantifierFires(op, t, f, n))
                }
            }
        }
    }

    /** A field-fill quantifier operand that needs the group/starred-scope treatment (IF49): a bare GROUP ref (a
     *  `Field` whose path is not a declared field), or a starred field/group WITHOUT a `Having` (a Star with a
     *  Having is the filtered-correlation path, handled by [starQuantTally]). A plain field list is not a scope. */
    internal fun hasFillScopeOperand(args: List<Expr>): Boolean = args.any {
        (it is Expr.Star && it.having == null) || (it is Expr.Field && ctxOf(it).declaredKind(it.path) == null)
    }

    /** Aggregate the [FillScope] tally across a fill quantifier's operands (a group scope expands to its
     *  descendant fields; the counts sum, the declared flags OR/AND). */
    internal fun fillScopeInfo(args: List<Expr>): FillScope {
        var t = 0; var f = 0; var n = 0; var unknown = false
        var allFilled = true; var anyEmpty = false
        for (a in args) {
            val path = scopePathOf(a)
            val c = ctxOf(a)
            // A starred index-field operand's instantiated tally is index-aware (mandatory-empty / duplicated
            // cells are not-check-relevant → the UNKNOWN bucket), so a fill quantifier over an index field matches
            // the kernel's `isWertAngegeben` (AtLeastOne stops firing on a fully-duplicated index; IF15/IF60).
            val fs = when {
                a is Expr.Star && a.having == null && a.path in indexFields -> indexStarFillScope(c, a.path)
                // A FILTERED operand must be tallied through its filter even here. [EvalContext.fillScope] takes
                // no `having` parameter at all, so before this branch the filter was silently dropped for every
                // operand of a list that any OTHER operand had diverted onto this path — see [filteredFillScope].
                a is Expr.Star && a.having != null -> filteredFillScope(c, a)
                else -> c.fillScope(path, isGroup = c.declaredKind(path) == null)
            }
            t += fs.tInst; f += fs.fInst; n += fs.nInst; unknown = unknown || fs.hasUnknownInst
            allFilled = allFilled && fs.allFilledDeclared
            anyEmpty = anyEmpty || fs.anyEmptyDeclared
        }
        return FillScope(t, f, n, unknown, allFilled, anyEmpty)
    }

    /**
     * The [FillScope] of a `Having`-FILTERED starred operand, tallied over the rows the filter KEEPS — the same
     * kept-row scan and the same 3-state classification as [naiveStarQuantTally], which is the measured path a
     * filtered operand takes when it is alone in its list.
     *
     * **Why this exists.** A fill quantifier is diverted onto the group/starred-scope path as soon as ANY operand
     * qualifies ([hasFillScopeOperand]), and that path called [EvalContext.fillScope] for EVERY operand — a
     * function with no `having` parameter, so the filter could not be honoured there even in principle. A mixed
     * list holding one unfiltered star (which diverts) and one filtered star (which is then mis-tallied) therefore
     * counted the filtered operand over ALL its rows. Measured against both kernel strategies on a document whose
     * only filled cell sits in the row the filter DROPS: `NoFieldFilled` returned false where the kernel fires and
     * `AtLeastOneFieldFilled` returned true where it does not — opposite directions, so the evidence is about the
     * dropped filter and not about either quantifier's verdict ([IF287]'s class; `MixedFillScopeHavingDiffTest`).
     *
     * **The DECLARED-range flags are derived from the same kept-row scan, and that is a measured choice rather than
     * a convenience.** A filter is a predicate over a row, so it cannot be evaluated on a repetition that does not
     * exist — there is no kept/dropped answer for an un-instantiated declared rep. The kernel's answer for the
     * instantiated case is measured (`AllFieldsFilled` fires over a filled kept row while an unfiltered reading
     * sees the dropped row's empty cell), and every row of that measurement has its full declared range
     * instantiated, so the interaction between a filter and an un-instantiated declared TAIL is deliberately left
     * unmeasured rather than guessed at here.
     */
    private fun filteredFillScope(c: EvalContext, star: Expr.Star): FillScope {
        val having = star.having ?: return c.fillScope(star.path, isGroup = false)
        var t = 0
        var f = 0
        var n = 0
        var unknown = false
        for (rc in c.rowContexts(star.path.substringBeforeLast('/'), star.bindDepth)) {
            if (!havingInterp(rc).fires(having)) continue
            n++
            when {
                rc.presenceUnknown(star.path) -> {}
                !rc.filled(star.path) -> f++
                rc.field(star.path) is Val.Unknown -> unknown = true
                else -> t++
            }
        }
        return FillScope(t, f, n, unknown, allFilledDeclared = f == 0 && !unknown, anyEmptyDeclared = f > 0)
    }

    /** Whether a field-fill quantifier fires over a group/starred scope — `AllFieldsFilled`/`NotAllFieldsFilled`
     *  use the DECLARED range, `FieldsNotCollectivelyFilled` mixes, the rest use the instantiated tally (IF49). */
    private fun fillScopeFires(op: PredicateOp, fs: FillScope): Boolean = when (op) {
        PredicateOp.ALL_FIELDS_FILLED -> fs.allFilledDeclared
        PredicateOp.NOT_ALL_FIELDS_FILLED -> fs.anyEmptyDeclared
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED -> fs.tInst >= 1 && fs.anyEmptyDeclared
        else -> quantifierFires(op, fs.tInst, fs.fInst, fs.nInst)
    }

    private fun scopePathOf(e: Expr): String = when (e) {
        is Expr.Field -> e.path
        is Expr.Star -> e.path
        else -> throw UnsupportedConstructException("not a fill-scope operand: $e")
    }

    // ---- COMPUTE-mode fill-quantifier scans (IF85 — the kernel's RuntimeController read order) --------------

    /**
     * COMPUTE-mode field-fill quantifiers — the kernel's ordered SCAN, not validation's tally: each visited
     * cell is a READ (an INVALID one poisons, [PoisonedReadException]), and each operator stops at its deciding
     * cell, so a cell after the stop is never read (kernel `mindestensEineEntityAngegeben` stops at the first
     * filled; `keineEntityAngegeben` at the first filled; `alleEntitiesAngegeben` / `nichtAlleOderKeine…` at the
     * first empty — the latter two over the DECLARED range, where an un-instantiated rep reads empty without a
     * value read). `NotExactlyOne` and `FieldsNotCollectivelyFilled` compose two scans and restart the second
     * scan from the beginning, like the kernel.
     * During compute there is no not-relevant UNKNOWN (the kernel resets the sets and throws instead), so a
     * cell is simply FILLED or EMPTY. Locked by `adapter.laws.ComputePoisonReadDiffTest`.
     */
    private fun computeFillQuantifier(op: PredicateOp, args: List<Expr>): Tri = when (op) {
        PredicateOp.AT_LEAST_ONE_FIELD_FILLED -> Tri.of(scanFilled(args, declaredTail = false).any { it })
        PredicateOp.NO_FIELD_FILLED -> Tri.of(scanFilled(args, declaredTail = false).none { it })
        PredicateOp.ALL_FIELDS_FILLED -> Tri.of(scanFilled(args, declaredTail = true).all { it })
        PredicateOp.NOT_ALL_FIELDS_FILLED -> Tri.of(!scanFilled(args, declaredTail = true).all { it })
        PredicateOp.MORE_THAN_ONE_FIELD_FILLED -> {
            var seen = 0
            Tri.of(scanFilled(args, declaredTail = false).any { it && ++seen > 1 })
        }
        // The kernel's two-pass composition: TRUE when NONE is filled, else the more-than-one scan decides.
        PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED ->
            if (scanFilled(args, declaredTail = false).none { it }) Tri.TRUE
            else {
                var seen = 0
                Tri.of(scanFilled(args, declaredTail = false).any { it && ++seen > 1 })
            }
        // at-least-one filled AND not-all filled (kernel `entitiesNichtGemeinsamAngegeben`, two passes).
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED ->
            if (scanFilled(args, declaredTail = false).none { it }) Tri.FALSE
            else Tri.of(!scanFilled(args, declaredTail = true).all { it })
        else -> throw UnsupportedConstructException("not a field-fill quantifier: $op")
    }

    /**
     * The lazy filled/empty cell stream of a fill quantifier's operands, in the kernel's read order — operands
     * in list order, a star's rows in row order, a bare group's fields in declaration order with every field's
     * declared repetitions interleaved before the next field, and a `Having` filter evaluated per row inside the
     * scan (its reads can poison too). An INVALID visited cell throws ([PoisonedReadException]); an uninstantiated
     * declared repetition reads empty without a value read, so it cannot poison.
     */
    private fun scanFilled(args: List<Expr>, declaredTail: Boolean): Sequence<Boolean> = sequence {
        // A starred GROUP expands here through the SHARED owner, which is the point rather than a convenience.
        // The bare-group arm below already expanded and the starred one did not, so this operator was correct on
        // the validation path — which dispatches on `fillScope(isGroup = …)` — and read ZERO cells on the compute
        // path. A mode split is the worst member of the class: the operator is demonstrably right wherever the
        // estate pairs it and wrong on the path nobody paired. Measured on both kernel strategies with every cell
        // filled, `NoFieldFilled(Charges*)` answered TRUE here and `AtLeastOneFieldFilled(Charges*)` answered
        // FALSE — opposite directions, which is the signature of a quantifier over an EMPTY selection rather than
        // of a wrong verdict (IF287's class, seventh instance).
        for (a in expandGroupStars(args)) when {
            a is Expr.Star && a.having == null -> {
                val c = ctxOf(a)
                for (cell in c.starScanCells(a.path, a.bindDepth, declaredTail)) yield(filledOrPoison(cell))
            }
            a is Expr.Star -> {
                // A filtered star: the filter runs per row inside the scan (its reads poison row-correctly); a
                // kept candidate retains path+reps+raw through the complete call-local formal oracle. An
                // un-instantiated declared rep cannot pass a filter, so there is no declared tail.
                val group = a.path.substringBeforeLast('/')
                for (rc in ctxOf(a).rowContexts(group, a.bindDepth)) {
                    if (!havingInterp(rc).fires(a.having!!)) continue
                    yield(filledOrPoison(scanCell(a.path, rc)))
                }
            }
            a is Expr.Field && ctxOf(a).declaredKind(a.path) == null -> {
                // A GROUP scope: scan its contained fields in DECLARATION order, each a lazy filled/empty read that
                // poisons on a malformed cell — so an operator's early-stop skips a later invalid cell, the kernel's
                // ordered getAllContainedFields scan (IF147). Was an eager whole-group tally that poisoned on ANY
                // unknown regardless of a preceding decisive cell (the IF147 over-poison, probe-confirmed).
                val c = ctxOf(a)
                for (cell in c.containedFieldCells(a.path, declaredTail)) yield(filledOrPoison(cell))
            }
            else -> yield(
                when (presenceTri(a)) {
                    Tri.TRUE -> true
                    // No compute-time not-relevant state exists (the kernel resets it; invalidity THROWS in
                    // presenceTri/filledTri) — a residual UNKNOWN (unbound `$`, invalid parallel index) reads empty.
                    else -> false
                },
            )
        }
    }

    private fun scanCell(path: String, ctx: EvalContext): ScanCell {
        val state = ctx.cellState(path)
        return ScanCell(
            path,
            ctx.repsOf(path),
            ctx.displayValue(path),
            state,
            if (state == CellState.EMPTY) null else ctx.field(path),
        )
    }

    internal fun filledOrPoison(cell: ScanCell): Boolean = when (
        if (cell.baseState == CellState.FILLED) computeScanState?.invoke(cell) ?: cell.baseState else cell.baseState
    ) {
        CellState.INVALID -> throw PoisonedReadException(cell.path)
        CellState.FILLED -> true
        CellState.EMPTY -> false
    }

    /**
     * `Valid(field, "Name")` / `Invalid(field, "Name")` — predefined-type validity (the kernel's client-SPI
     * `IPredefinedType`; KERNEL-FINDINGS §8). An EMPTY field is "not specified" → not evaluated (UNKNOWN → no
     * fire, kernel-probed); a present value is TRUE/FALSE by the registered type's [PredefinedType.accepts]
     * (`Invalid` is its complement). An UNREGISTERED type name is **unsupported** (fail-fast) — the consumer
     * must supply the validator, mirroring the kernel's client SPI.
     */
    private fun predefinedTypeValidity(args: List<Expr>, expectValid: Boolean): Tri {
        val path = pathOf(args[0])
        val typeName = (args[1] as? Expr.StrLit)?.v
            ?: throw UnsupportedConstructException("predefined-type validity needs a string type name, got ${args[1]}")
        // Declarative first (monomorphic), then a code-based type; unregistered in BOTH → unsupported (fail-fast).
        // The `Valid`/`Invalid` condition operator needs only the accept/reject boolean — a code-based type
        // accepts iff its `check` returns null (no rejection); the project code rides the per-cell formal error.
        val accept: (String) -> Boolean = predefinedTypes[typeName]?.let { it::accepts }
            ?: customFieldTypes[typeName]?.let { customType ->
                { value: String ->
                    // Explicit Valid/Invalid uses the kernel's validate-without-error-reporting path: there is no
                    // declaring field, so bounds are absent; both strategies pass Locale.GERMANY and canonical data.
                    customType.check(value, CustomFieldValidationContext("de_DE")) == null
                }
            }
            ?: throw UnsupportedConstructException("unknown predefined type \"$typeName\" — register it in the predefined-type or custom-field-type registry")
        if (!ctx.filled(path)) return Tri.UNKNOWN
        val value = ctx.displayValue(path) ?: return Tri.UNKNOWN
        return Tri.of(accept(value) == expectValid)
    }

    /**
     * The three-valued presence of a field — the kernel's `isWertAngegeben`: a field that is **not
     * check-relevant** (present but formally invalid — a malformed value, or a duplicate index) is **UNKNOWN**,
     * NOT a plain TRUE; an empty (check-relevant but unfilled) field is FALSE; a valid present value is TRUE. So
     * `FieldFilled(malformedQty) And …` does NOT fire (the And goes UNKNOWN), matching the kernel — a presence
     * check on a malformed field is not-check-relevant even though it reads no value. (KERNEL-FINDINGS §3.)
     */
    internal fun filledTri(c: EvalContext, path: String): Tri = when {
        c.presenceUnknown(path) -> Tri.UNKNOWN   // presence itself is 3VL (invalid parallel index) — before filled
        // COMPUTE mode: a presence check READS the cell — an invalid one poisons (IF85), row-correct via the
        // context's own cellState (a `Having` filter row judges ITS row's cell, not the computation's).
        computeMode && c.cellState(path) == CellState.INVALID -> throw PoisonedReadException(path)
        // PARTIAL validation: a non-relevant cell is unavailable in BOTH directions — the kernel's
        // `isWertAngegeben` decides relevance before the value read, so an EMPTY non-relevant operand
        // is not an empty witness (`NoFieldFilled`/`NotAllGroupsFilled`/… stay silent over it). Checked
        // BEFORE the fill read; row-correct via the context's own reps. Null outside partial.
        cellRelevant?.invoke(path, c.repsOf(path)) == false -> Tri.UNKNOWN
        !c.filled(path) -> Tri.FALSE
        invalid(path) -> Tri.UNKNOWN
        else -> Tri.TRUE
    }

    /** A FIELD-fill list quantifier's operand tally as `(t, f)` — `t` = TRUE (present + check-relevant), `f` =
     *  FALSE (check-relevant but empty); the rest are UNKNOWN (present but not check-relevant — a malformed
     *  value), counted in NEITHER, matching the kernel's `isWertAngegeben` (the shared [presenceTri], which also
     *  resolves a SEMANTIC-INDEX operand to its one matched cell — `SemanticIndexPresenceDiffTest`). The kernel
     *  counts only TRUE for "filled" and only FALSE for "empty"; a malformed field is neither — so e.g.
     *  `AtLeastOneFieldFilled` does NOT fire on a lone malformed field. A `$`-marked operand tallies against the
     *  enclosing row (ctxOf). The GROUP-fill family routes through [groupFillStates] instead ([quantCounts]). */
    private fun quantTally(args: List<Expr>): Pair<Int, Int> {
        var t = 0
        var f = 0
        for (a in args) {
            when (presenceTri(a)) {
                Tri.TRUE -> t++
                Tri.FALSE -> f++
                Tri.UNKNOWN -> {}
            }
        }
        return t to f
    }

    /**
     * The GROUP-fill quantifier's common entity list as a LAZY per-operand state stream in the kernel's read
     * order — the single source consumed as a `(t, f, n)` tally in validation ([quantCounts]) and as a
     * short-circuiting boolean fold in compute ([computeGroupFillQuantifier], IF219). Per operand:
     *  - a checked FIELD (a declared kind) contributes its three-way presence [presenceTri] — in COMPUTE mode a
     *    malformed read POISONS ([PoisonedReadException]); the mode-dependence lives entirely in [filledTri];
     *  - a bare GROUP contributes the IF193 product state (`definitelyFilled` → TRUE, `definitelyEmpty` → FALSE,
     *    else UNKNOWN) — structural in compute (`erroneous`=false, so a malformed-only group is FILLED, never
     *    poison);
     *  - a STARRED GROUP scope (`NoGroupFilled`/`AtLeastOneGroupFilled`, the only forms the kernel lets carry a
     *    `*`) yields one state per selected environment: a repeatable terminal row is FILLED structurally
     *    (IF46/IF68), while a non-repeatable terminal below starred ancestors retains its ordinary group-product
     *    state (SPEC-2026-07-26-02);
     * Yielded lazily so a compute-mode consumer that decides early never reads (and never poisons on) a later
     * operand — the kernel's ordered `…EntityAngegeben` read.
     */
    private fun groupFillStates(args: List<Expr>): Sequence<Tri> = sequence {
        for (arg in args) when {
            arg is Expr.Star -> {
                for (rc in ctxOf(arg).groupListEnvironments(arg.path, arg.bindDepth)) {
                    if (arg.having != null && !havingInterp(rc).fires(arg.having)) continue
                    val state = groupPresence(rc, arg.path)
                    yield(when {
                        state.definitelyFilled -> Tri.TRUE
                        state.definitelyEmpty -> Tri.FALSE
                        else -> Tri.UNKNOWN
                    })
                }
            }
            ctxOf(arg).declaredKind(pathOf(arg)) != null -> yield(presenceTri(arg))
            else -> {
                val state = groupPresence(ctxOf(arg), pathOf(arg))
                yield(when {
                    state.definitelyFilled -> Tri.TRUE
                    state.definitelyEmpty -> Tri.FALSE
                    else -> Tri.UNKNOWN
                })
            }
        }
    }

    /** The quantifier's `(TRUE, FALSE, N)` tally. The GROUP-fill family folds [groupFillStates] (a checked field,
     *  a group's product state, and one state per starred-group environment — so a STARRED group scope may be
     *  MIXED with other operands, and `n` is the yielded count, not `args.size`). A single-star FIELD-fill
     *  operand expands to its filtered rows ([starQuantTally]); else a plain field list ([quantTally]). */
    internal fun quantCounts(op: PredicateOp, args: List<Expr>): Triple<Int, Int, Int> {
        if (op.countsGroups) {
            var t = 0
            var f = 0
            var n = 0
            for (state in groupFillStates(args)) {
                when (state) {
                    Tri.TRUE -> t++
                    Tri.FALSE -> f++
                    Tri.UNKNOWN -> {}
                }
                n++
            }
            return Triple(t, f, n)
        }
        val single = args.singleOrNull()
        if (single is Expr.Star) return starQuantTally(single)
        val (t, f) = quantTally(args)
        return Triple(t, f, args.size)
    }

    /**
     * COMPUTE-mode group-list quantifiers — the kernel's ordered read of the common entity list, not validation's
     * tally: [groupFillStates] is consumed lazily and each operator STOPS at its deciding operand, so a malformed
     * FIELD operand AFTER the decision is never read and cannot poison (a filled/empty operand before it decided
     * first) — the read-order divergence IF219 fixed. A FIELD operand read is poison-capable; a GROUP operand
     * (bare or starred) is structural (never poison). Mirrors [computeFillQuantifier] for the field-fill family
     * (IF85); the composite `GroupsNotCollectivelyFilled` composes two passes like `FieldsNotCollectivelyFilled`.
     */
    private fun computeGroupFillQuantifier(op: PredicateOp, args: List<Expr>): Tri = when (op) {
        PredicateOp.AT_LEAST_ONE_GROUP_FILLED -> Tri.of(groupFillStates(args).any { it == Tri.TRUE })
        PredicateOp.NO_GROUP_FILLED -> Tri.of(groupFillStates(args).none { it == Tri.TRUE })
        PredicateOp.ALL_GROUPS_FILLED -> Tri.of(groupFillStates(args).all { it == Tri.TRUE })
        PredicateOp.NOT_ALL_GROUPS_FILLED -> Tri.of(groupFillStates(args).any { it == Tri.FALSE })
        PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED ->
            if (groupFillStates(args).none { it == Tri.TRUE }) Tri.FALSE
            else Tri.of(groupFillStates(args).any { it == Tri.FALSE })
        else -> throw UnsupportedConstructException("not a group-fill quantifier: $op")
    }

    /**
     * Tally a STARRED field-list quantifier operand — `AtLeastOneFieldFilled(Group* / Field Having …)`: iterate
     * the star's group rows, keep those the optional `Having` filter accepts, and count each kept row's cell as
     * TRUE (present + check-relevant) / FALSE (empty) / neither (malformed) — the same 3-state as [quantTally].
     * Indexable `$` equality/comparator correlations use keyed/ordered tallies; arbitrary filters retain the
     * general scan. Returns `(t, f, n = kept rows)`.
     */
    private fun starQuantTally(star: Expr.Star): Triple<Int, Int, Int> {
        val group = star.path.substringBeforeLast('/')
        return when (val plan = correlationPlan(star, group)) {
            is KeyedCorrelationPlan -> hashJoinTally(star, group, plan)
            is OrderedCorrelationPlan -> orderedCorrelationTally(star, group, plan)
            null -> naiveStarQuantTally(star, group)
        }
    }

    /** Whether [star] binds ABOVE its leaf group's parent — a multi-star operand (`A* / B* / F`, IF94). */
    private fun isMultiStar(star: Expr.Star): Boolean =
        star.bindDepth >= 0 && star.bindDepth < star.path.trim('/').split('/').size - 2

    /**
     * Runtime-qualified correlation plan. A multi-star self-exclusion stays general because it excludes every row
     * sharing the outer leaf index, not one physical row. Ordered plans are limited to kinds whose runtime order is
     * exactly [ValCompare.language]; non-UTC DATE_TIME needs instant ordering and therefore stays on the fallback.
     */
    private fun correlationPlan(star: Expr.Star, group: String): CorrelationPlan? {
        val plan = star.having?.let { CorrelationJoin.parse(it, group) } ?: return null
        if (isMultiStar(star) && plan.selfExcluded) return null
        if (plan is OrderedCorrelationPlan && !orderedCorrelationSupported(plan)) return null
        return plan
    }

    private fun orderedCorrelationSupported(plan: OrderedCorrelationPlan): Boolean {
        val innerKind = ctx.declaredKind(plan.innerField)
        val outerKind = ctx.declaredKind(plan.outerField)
        if (innerKind == null || innerKind != outerKind) return false
        return when (innerKind) {
            Kind.NUMBER, Kind.STRING, Kind.DATE, Kind.TIME -> true
            Kind.DATE_TIME -> timeZone == ModelTimeZone.UTC
            Kind.BOOLEAN, Kind.CONFIRM, Kind.DATE_RANGE -> false
        }
    }

    /** Deterministic shape-dispatch seam for the plan/fallback lock. */
    internal fun correlationStrategy(star: Expr.Star): CorrelationStrategy {
        val group = star.path.substringBeforeLast('/')
        return when (correlationPlan(star, group)) {
            is KeyedCorrelationPlan -> CorrelationStrategy.KEYED
            is OrderedCorrelationPlan -> CorrelationStrategy.ORDERED
            null -> CorrelationStrategy.NESTED
        }
    }

    /**
     * The O(N²) inner-scan `(t, f, n)` tally of a starred filter quantifier — the general fallback for an
     * arbitrary `Having`, and the **reference** the hash-join fast path must equal on a recognized correlation
     * `Having` (locked by [correlationTallyBothWays]). Iterates the group's rows, keeps those the filter
     * accepts, and 3-state-counts each kept row's star cell (present / empty / not-check-relevant).
     */
    private fun naiveStarQuantTally(star: Expr.Star, group: String): Triple<Int, Int, Int> {
        var t = 0
        var f = 0
        var n = 0
        for (rc in ctx.rowContexts(group, star.bindDepth)) {
            if (star.having != null && !havingInterp(rc).fires(star.having)) continue
            n++
            when {
                rc.presenceUnknown(star.path) -> {}
                !rc.filled(star.path) -> f++
                // filled-but-invalid, ROW-correct: the KEPT row's own cell (rc.field → resolveValue → Unknown,
                // IF84) — the old `invalid(star.path)` resolved at the ENCLOSING rule row's reps, so a per-row
                // rule whose OWN cell was invalid poisoned the tally of a valid KEPT row (IF105); a filter-
                // DROPPED row's invalid cell never suppresses (the kernel filter drops first, IF94).
                rc.field(star.path) is Val.Unknown -> {}
                else -> t++
            }
        }
        return Triple(t, f, n)
    }

    // ── Property-test seams — internal, consumed only by the commonTest property pack ─────────────────

    /**
     * **Property 1 (lazy ≡ eager polarity).** Whether the production lazy-polarity walk [evalTypedLazy] agrees
     * with the full eager walk [evalTyped] for [cond] in this context: the same truth, and — when the rule FIRES
     * (truth TRUE) — the same [MsgType] polarity. That is exactly the invariant [firedType] relies on (it walks
     * lazily and reads the type only on a TRUE node). Compared only when the row is [gated] (else [firedType]
     * would not walk at all, so there is nothing to compare). The property sweeps this over sampled (model, doc).
     */
    internal fun lazyEagerConsistent(cond: Cond): Boolean {
        if (!gated(cond)) return true
        val eager = evalTyped(cond)
        val lazy = evalTypedLazy(cond)
        return eager.truth == lazy.truth && (eager.truth != Tri.TRUE || eager.type == lazy.type)
    }

    /**
     * **Property 6 (indexed correlation ≡ nested-loop).** For a starred filter quantifier [star] whose `Having`
     * has a keyed/ordered plan, pair the indexed tally with the general scan so the property pack can assert exact
     * equality. Returns null for the documented fallback boundary.
     */
    internal fun correlationTallyBothWays(star: Expr.Star): Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>>? {
        val group = star.path.substringBeforeLast('/')
        val fast = when (val plan = correlationPlan(star, group)) {
            is KeyedCorrelationPlan -> hashJoinTally(star, group, plan)
            is OrderedCorrelationPlan -> orderedCorrelationTally(star, group, plan)
            null -> return null
        }
        return fast to naiveStarQuantTally(star, group)
    }

    /**
     * The `(t, f, n)` of a filtered quantifier whose `Having` is an equality self-correlation — computed by a
     * HASH JOIN (O(1) per outer row) instead of the O(N²) inner scan: look up the outer row's bucket by its
     * OUTER-field values, then remove the outer row's own contribution when a self-exclusion is present (it sits
     * in its own bucket iff its inner-field values equal its outer-field values). Returns exactly what the scan
     * would for well-formed data (the differential + fuzz lock it). Buckets are built + cached by the Document.
     */
    private fun hashJoinTally(star: Expr.Star, group: String, join: KeyedCorrelationPlan): Triple<Int, Int, Int> {
        val buckets = ctx.correlationBuckets(group, join.innerFields, star.path, star.bindDepth)
        val outerKey = join.outerFields.map { p -> if (invalid(p)) Val.Unknown else ctx.field(p) }
        if (outerKey.any { it is Val.Unknown }) return Triple(0, 0, 0)   // an Unknown outer value never matches
        val tfn = buckets[outerKey] ?: return Triple(0, 0, 0)
        var tally = Triple(tfn[0], tfn[1], tfn[2])
        if (join.selfExcluded && ctx.evaluated) {
            val outerInnerKey = join.innerFields.map { p -> if (invalid(p)) Val.Unknown else ctx.field(p) }
            if (outerInnerKey == outerKey) {           // the outer row is in its own bucket → drop it
                tally = subtractOwnContribution(star, tally)
            }
        }
        return tally
    }

    /** Ordered-prefix equivalent of [hashJoinTally], O(log distinct values) for one outer row. */
    private fun orderedCorrelationTally(
        star: Expr.Star,
        group: String,
        plan: OrderedCorrelationPlan,
    ): Triple<Int, Int, Int> {
        val outer = if (invalid(plan.outerField)) Val.Unknown else ctx.field(plan.outerField)
        if (outer is Val.Unknown) return Triple(0, 0, 0)
        var tally = ctx.orderedCorrelation(group, plan.innerField, star.path, star.bindDepth).tally(plan.op, outer)
        if (plan.selfExcluded && ctx.evaluated) {
            val inner = if (invalid(plan.innerField)) Val.Unknown else ctx.field(plan.innerField)
            if (inner !is Val.Unknown && orderedComparisonMatches(plan.op, inner, outer)) {
                tally = subtractOwnContribution(star, tally)
            }
        }
        return tally
    }

    private fun subtractOwnContribution(star: Expr.Star, tally: Triple<Int, Int, Int>): Triple<Int, Int, Int> {
        var (trueCount, falseCount, rows) = tally
        rows--
        when {
            ctx.presenceUnknown(star.path) -> {}
            !ctx.filled(star.path) -> falseCount--
            // A filled-but-invalid own cell contributes to neither truth bucket (IF105).
            ctx.field(star.path) is Val.Unknown -> {}
            else -> trueCount--
        }
        return Triple(trueCount, falseCount, rows)
    }

    /**
     * `FieldValueIncludedInValueList(field, m1, m2, …)` — TRUE when [args]`[0]`'s value equals a member, via
     * the same typed equality as `==` (string members compare as text, number members numerically; the
     * kernel rejects mixed types at compile, `MVK_INVALID_TYPES_FOR_COMPARISON`). An UNKNOWN operand (an
     * empty string/enum field) yields UNKNOWN, so NEITHER the included nor the `Not` form fires on it —
     * matching the kernel (a value-list check is not evaluated on a value-less field).
     */
    private fun valueInList(args: List<Expr>): Tri {
        val v = evalExpr(args[0])
        if (v is Val.Unknown) return Tri.UNKNOWN
        return Tri.of(args.drop(1).any { ValCompare.equal(v, evalExpr(it)) })
    }

    /**
     * `DateRangesOverlap(op0, op1, …)` — **any-pair among ALL** operands' kept, filled range cells (kernel
     * `datumsBereicheUeberlappend`): every operand's cells feed ONE growing set in operand order, and the
     * predicate fires on the FIRST range overlapping any already-seen member. There is NO distinguished first
     * operand — so the SAME row's cell arriving via a scalar ref AND via the star is two members whose identical
     * range "overlaps" (the self-pair), and a list-internal pair fires even when a scalar operand is disjoint.
     * Empty/malformed cells are SKIPPED (no unknown-suppression); an INVALID range never overlaps ([rangesOverlap]).
     *
     * Polarity follows the reached scan: [containsFilter] becomes sticky only after a kept, filled cell from a
     * filter-bearing operand is processed. An empty/dropped filtered operand therefore cannot taint a later
     * pair, while a reached filtered range can taint a later pair even when it is disjoint from that pair.
     * Returns the (truth, polarity) [Res] so [evalPred] and [predType] share ONE scan (KERNEL-FINDINGS §6, IF72).
     */
    internal fun overlapAny(args: List<Expr>): Res {
        val seen = mutableListOf<Val.Range>()
        var containsFilter = false
        for (arg in args) {
            val filtered = arg is Expr.Star && arg.having != null
            for (cell in overlapCells(arg)) {
                if (filtered) containsFilter = true
                if (seen.any { rangesOverlap(cell, it) })
                    return Res.of(Tri.TRUE, if (containsFilter) MsgType.OMISSION else MsgType.VALUE)
                seen.add(cell)
            }
        }
        return Res.of(Tri.FALSE, MsgType.VALUE)
    }

    /**
     * `AtLeastOneDateRangeOverlaps(scalar In list…)` — genuinely scalar-vs-list (kernel
     * `mindestensEinDatumsBereichUeberlappendFL`): the single unstarred [args]`[0]` range is compared against
     * each list cell; it is NOT a member of the set, so there is no self-pair. A not-filled, malformed, or
     * formally unavailable scalar exits FALSE before the list scan. Polarity belongs only to the MATCHED list
     * container: an earlier filtered-but-disjoint or empty container cannot taint a later unfiltered match
     * (KERNEL-FINDINGS §6, IF72).
     */
    internal fun atLeastOneOverlap(args: List<Expr>): Res {
        val scalar = evalExpr(args[0]) as? Val.Range ?: return Res.of(Tri.FALSE, MsgType.VALUE)
        for (i in 1 until args.size) {
            val arg = args[i]
            val filtered = arg is Expr.Star && arg.having != null
            for (cell in overlapCells(arg)) {
                if (rangesOverlap(scalar, cell))
                    return Res.of(Tri.TRUE, if (filtered) MsgType.OMISSION else MsgType.VALUE)
            }
        }
        return Res.of(Tri.FALSE, MsgType.VALUE)
    }

    /**
     * The kept, FILLED range cells of an overlap operand, in row order — empties/malformed dropped (the kernel's
     * `isWertAngegeben != TRUE → continue`). A scalar operand contributes its one range (or nothing); a starred
     * operand expands over its group's rows, and a `Having` KEEPS a row iff the filter is TRUE (the kernel
     * compiles the filter to a BOOLEAN — a runtime-UNKNOWN comparison, e.g. a malformed operand, is not-TRUE →
     * the row is dropped). A `$`-correlation term has no enclosing iteration for a plain predicate's arg, so the
     * codegen cannot compile it and the whole term is trivially TRUE ([evalCmp]/[evalPred] short-circuit under
     * [outerUnbound]) — that is why a `!= $RuleGroup` / `== $RuleGroup` self-"exclusion" keeps ALL rows, while a
     * malformed real operand drops them. Distinct from the quantifier-`Having` path ([starOperandVals], `$` bound
     * to the enclosing row). (KERNEL-FINDINGS §6, IF72.)
     */
    private fun overlapCells(arg: Expr): List<Val.Range> = when {
        // A GROUP operand names no declared field, so BOTH arms below were blind to it and by different
        // mechanisms: the starred arm read `star` at the group path (zero cells, a silent non-overlap), while the
        // unstarred arm asked `evalExpr` for a range at a path holding no value and THREW `no kind declared` —
        // which the degrade net does not catch, so it sank the whole evaluation. `DateRangesOverlap` refuses a
        // group operand outright, but the plural form ADMITS one in its `In` slot ([KF187]), which is what made
        // this reachable and what corrected IF287's reason for leaving `filteredStarCells` unexpanded.
        // An empty group contributes no range rather than recursing on itself.
        namesAGroup(arg) -> expandOneGroupOperand(arg).let { expanded ->
            if (expanded.singleOrNull() === arg) emptyList() else expanded.flatMap { overlapCells(it) }
        }
        arg is Expr.Star -> filteredStarCells(arg).filterIsInstance<Val.Range>()
        else -> listOfNotNull(evalExpr(arg) as? Val.Range)
    }

    /**
     * The FILLED cells of a starred rep-list operand of a plain boolean predicate (`DateRangesOverlap`,
     * `FieldValuesNotUnique`), honoring an optional `Having` — the SHARED filter path. Without a `Having`:
     * [EvalContext.star] (empties dropped, a malformed cell → [Val.Unknown]). With one: keep a row iff the filter
     * FIRES (TRUE) — the kernel compiles the filter to a boolean, so a malformed operand (runtime-UNKNOWN) drops
     * the row, and a `$`-correlation term (no enclosing iteration here) is trivially TRUE ([evalCmp] short-circuit
     * via [overlapFilterInterp]); a kept row's empty cell is dropped, a kept malformed cell surfaces as
     * [Val.Unknown]. Distinct from the quantifier-`Having` path ([starOperandVals], `$` bound). KERNEL-FINDINGS §6.
     */
    private fun filteredStarCells(star: Expr.Star): List<Val> {
        val c = ctxOf(star)
        if (star.having == null) return c.star(star.path, star.bindDepth)
        return c.rowContexts(star.path.substringBeforeLast('/'), star.bindDepth)
            .filter { rc -> overlapFilterInterp(rc).fires(star.having) }
            .mapNotNull { rc -> if (rc.filled(star.path)) rc.field(star.path) else null }
    }

    /** `value PatternMatched/PatternViolated "regex"` — anchored full-value match; empty → not evaluated. */
    internal fun evalPattern(p: Cond.Pattern): Tri {
        val v = evalExpr(p.value)
        if (v is Val.Unknown) return Tri.UNKNOWN
        val text = (v as? Val.Txt)?.v ?: error("pattern comparison needs a string value, got $v")
        val pattern = (p.pattern as Expr.StrLit).v
        val matched = config.patternMatches(pattern, text)
        return Tri.of(if (p.expectMatch) matched else !matched)
    }

    internal fun evalCmp(c: Cond.Cmp): Tri {
        // Overlap-predicate Having: a comparison touching an unbound `$` cannot be compiled (no enclosing
        // iteration) → the kernel treats the term as trivially TRUE (KERNEL-FINDINGS §6). This is why a
        // `$`-self-"exclusion" keeps every row, unlike a malformed real operand (which drops, evaluated below).
        if (outerUnbound && (hasUnboundOuter(c.left) || hasUnboundOuter(c.right))) return Tri.TRUE
        val l = evalCmpOperand(c.left, c.right)
        val r = evalCmpOperand(c.right, c.left)
        if (l is Val.Unknown || r is Val.Unknown || l is Val.DomainFailure || r is Val.DomainFailure) return Tri.UNKNOWN
        // The kernel's `vergleicheSTRING` returns false when EITHER operand is the empty string — for `==` AND
        // `!=` alike (an empty operand never matches and never differs). So an empty `RangeAsString` result, or a
        // literal `""`, suppresses the comparison (it does not fire) rather than comparing as a value. An empty
        // string *field* already arrives as Unknown (handled above); this covers the non-field empty strings.
        if ((l is Val.Txt && l.v.isEmpty()) || (r is Val.Txt && r.v.isEmpty())) return Tri.UNKNOWN
        // A legal mixed DATE/DATE_TIME ordering compares two instants on the model-zone timeline. Lexical ISO
        // ordering is observably wrong at exact midnight (`date < dateT00` by string length, but the instants
        // are equal). The typed authoring surface excludes mixed equality; this shared comparison still stays
        // total for every operator in a loaded model. `MixedDateTimeOrderingDiffTest` is the discriminator.
        if (l is Val.Txt && r is Val.Txt &&
            ((isIsoDate(l.v) && isIsoDateTime(r.v)) || (isIsoDateTime(l.v) && isIsoDate(r.v)))) {
            val li = if (isIsoDateTime(l.v)) dateTimeInstant(c.left) else dateInstant(c.left)
            val ri = if (isIsoDateTime(r.v)) dateTimeInstant(c.right) else dateInstant(c.right)
            if (li == null || ri == null) return Tri.UNKNOWN
            return compareInstants(c.op, li, ri)
        }
        // A DATETIME comparison under a non-UTC model zone compares zone-parsed INSTANTS (the kernel's
        // `vergleicheDATUM` on `VkDate` epoch millis), derived STRUCTURALLY so a chained `Add{Hours,…}` keeps
        // its exact instant — an ambiguous fall-back wall time re-parses as STANDARD, so the equal-looking
        // rendering of a daylight-side chain result is a DIFFERENT instant (KERNEL-FINDINGS §6). Under UTC the
        // canonical local ISO order IS the instant order; only a rendered tie can hide Now's millisecond remainder.
        if (l is Val.Txt && r is Val.Txt && isIsoDateTime(l.v) && isIsoDateTime(r.v) &&
            (timeZone != ModelTimeZone.UTC || l.v == r.v)) {
            val li = dateTimeInstant(c.left) ?: return Tri.UNKNOWN
            val ri = dateTimeInstant(c.right) ?: return Tri.UNKNOWN
            return compareInstants(c.op, li, ri)
        }
        // A DATE comparison under a non-UTC model zone likewise compares zone-resolved INSTANTS, for the same
        // reason and by the same structural route: a Date-valued shift retains the instant ITS mutation selected,
        // and over an ambiguous midnight that can differ from the instant the equal-looking label re-resolves to
        // (SPEC-2026-07-29-01). Both sides can always produce an instant — a field read derives one by the
        // ordinary parse resolution, which is what the kernel's `VkDate` holds for it — so this is not gated on
        // retention; gating it on retention would compare a shifted Date against a field by LABEL and diverge.
        // The footprint is provably one date: wherever midnight is unambiguous the derived instant and the label
        // order agree, and Berlin's pinned history has exactly one ambiguous midnight (1916-10-01).
        if (l is Val.Txt && r is Val.Txt && isIsoDate(l.v) && isIsoDate(r.v) && timeZone != ModelTimeZone.UTC) {
            val li = dateInstant(c.left)
            val ri = dateInstant(c.right)
            if (li != null && ri != null) {
                return compareInstants(c.op, li, ri)
            }
        }
        return when (c.op) {
            CmpOp.EQ -> Tri.of(ValCompare.equal(l, r))
            CmpOp.NE -> Tri.of(!ValCompare.equal(l, r))
            CmpOp.LT -> Tri.of(ValCompare.language(l, r) < 0)
            CmpOp.LE -> Tri.of(ValCompare.language(l, r) <= 0)
            CmpOp.GT -> Tri.of(ValCompare.language(l, r) > 0)
            CmpOp.GE -> Tri.of(ValCompare.language(l, r) >= 0)
        }
    }

    private fun compareInstants(op: CmpOp, left: Long, right: Long): Tri = when (op) {
        CmpOp.EQ -> Tri.of(left == right)
        CmpOp.NE -> Tri.of(left != right)
        CmpOp.LT -> Tri.of(left < right)
        CmpOp.LE -> Tri.of(left <= right)
        CmpOp.GT -> Tri.of(left > right)
        CmpOp.GE -> Tri.of(left >= right)
    }

    /** The 3VL presence of a field-valued predicate/quantifier operand — a plain field ref ([filledTri]) or a
     *  semantic index ([semanticIndexPresence]). Any other operand shape is not a presence operand. */
    internal fun presenceTri(e: Expr): Tri = when {
        outerUnknown(e) -> Tri.UNKNOWN   // an unbound `$` presence operand (overlap-predicate Having) → UNKNOWN
        e is Expr.SemanticIndex -> semanticIndexPresence(e)
        else -> filledTri(ctxOf(e), pathOf(e))
    }

    /**
     * `FieldValuesNotUnique(…)` — fires iff two of the operands' values are **equal**, i.e. the compared values are
     * not all distinct (kernel codegen). Two operand shapes, both kernel-probed and identical in semantics: a
     * **plain field list** `(f1, f2, …)` (distinctness across named sibling fields) and a **starred** operand
     * `(Items* / Count)` (distinctness across the repetition's rows — the no-`--group` alternative to
     * `RepetitionNotUnique`). Equality is [Aggregates.distinctKeyOrNull] (numbers numerically — `5` == `5.00`
     * across scales — strings as strings).
     *
     * The scan is **ORDERED and returns at the first duplicate** (SPEC-2026-08-05-01, measured across the 13-case
     * matrix in `FieldValuesNotUniqueDiffTest`), which is what makes the result POSITION-dependent and therefore
     * why truth and polarity must be decided in this one walk rather than by inspecting the operand list:
     *
     *  - A cell contributes nothing when it is **empty** OR when its stored value **cannot be converted** to the
     *    declared type. Both are skipped, and neither suppresses the rule: two equal unconvertible values do not
     *    duplicate (they never enter the comparison), while a duplicate elsewhere in the list still fires. Measured
     *    on Number and on Date.
     *  - A filtered operand escalates the firing to OMISSION only through a cell that **actually yielded a compared
     *    value**, and only if that happened **before** the duplicate was reached. So a filter authored after the
     *    duplicate, a filter whose kept cells are all empty, and a filter whose kept cells are all unconvertible all
     *    leave the firing a VALUE error.
     */
    internal fun fieldValuesNotUnique(args: List<Expr>): Res {
        // O(n) duplicate detection via canonical keys — the starred operand contributes ONE value per row, so the
        // old O(n²) pairwise equality scan went quadratic on a large repetition list (confirmed super-linear; the
        // kernel memoizes this via its RepetitionNotUniqueErrorCache). The key is the SAME keying
        // `NumberOfDifferentValues` uses (numbers scale-19-insensitive, strings/bools by themselves), so distinctness
        // and duplicate-detection agree; a `Range`/other never compares equal → no key → contributes nothing.
        val seen = HashSet<String>()
        var escalated = false
        for (a in args) {
            // A `Having` on a starred operand is kernel-probed to be ACCEPTED + EVALUATED (the overlap-predicate
            // keep-law; unlike SumOfProducts, which the kernel authoring-rejects), and filtering the rows changes
            // which values are compared for distinctness (IF72).
            val filtered = a is Expr.Star && a.having != null
            val cells = comparedCells(a)
            for (cell in cells) {
                val key = Aggregates.distinctKeyOrNull(cell) ?: continue   // unconvertible / uncomparable → skipped
                if (filtered) escalated = true
                if (!seen.add(key)) return Res.of(Tri.TRUE, if (escalated) MsgType.OMISSION else MsgType.VALUE)
            }
        }
        return Res.of(Tri.FALSE, MsgType.VALUE)
    }

    /**
     * The compared cells of ONE `FieldValuesNotUnique` operand, expanding a **GROUP** operand to its contained
     * fields recursively — the same `ExpandService` behaviour [starOperandVals] already implements for the value
     * aggregates, and which this operator did not share.
     *
     * **Measured before it was written, and it was a real divergence.** The kernel admits a group operand here
     * (KERNEL-FINDINGS KF173): arity treats the group slot as already-many, while the category gate reads the
     * expansion. Both kernel strategies then FIRE on a duplicate among the expanded cells, and this evaluator
     * returned FALSE — a silent false negative on a kernel-legal shape, which for a uniqueness rule means a
     * duplicate goes unreported. The unstarred arm failed because `pathOf` + `filled` ask about a group path
     * where no field is declared, and the starred arm because [filteredStarCells] does not expand. Locked by
     * `adapter.laws.FieldValuesNotUniqueGroupOperandDiffTest`.
     *
     * The expansion is deliberately **not** pushed into [filteredStarCells]: its other caller is the date-range
     * overlap operator, whose behaviour over a group operand is unmeasured, and widening a shared path past its
     * measured basis is the defect class CLAUDE.md's "never broader than its measured basis" rule names.
     */
    private fun comparedCells(a: Expr): List<Val> {
        val path = when (a) {
            is Expr.Star -> a.path
            is Expr.Field -> a.path
            else -> null
        }
        if (path != null && ctxOf(a).declaredKind(path) == null) {
            val contained = ctxOf(a).containedFields(path)
            if (contained.isNotEmpty()) {
                // The star's Having and binding ride each expanded field, exactly as starOperandVals preserves
                // them (IF99) — the filter belongs to the authored operand, not to one of its expanded cells.
                //
                // BOTH arms expand to a STARRED field, including the non-starred one. A contained field may live
                // under a repeatable SUBGROUP, and a plain field ref reads it at one ambient coordinate — which
                // silently dropped every row but the first, so a duplicate pairing a direct field with nested row 2
                // went unreported while both kernel strategies fire on it.
                //
                // The expanded field binds at the OPERAND'S OWN group depth, not at `-1`. `-1` means "the leaf
                // group's parent", which iterates the nested repetition fully only while nothing else anchors an
                // intermediate level — and iteration here is reference-driven, so a sibling ref can anchor one.
                // Measured: with the guard `FieldFilled(…/Handovers/Marker)` anchoring the middle level, a
                // duplicate spanning two `Handovers` rows' `Scans` fires on both kernel strategies and the
                // interpreter returned FALSE, because `groupScopePrefix` caps at the common prefix with the
                // ITERATING group and `-1` left that cap high enough to pin `Handovers`. Binding at the operand's
                // own depth keeps every level strictly below the operand free whatever the rule iterates, which is
                // the same rule `ModelStaticPlans.expandMessageReference` applies to the reference channel — the
                // two channels of one operand must not disagree about its extent.
                // The depth comes from `ast.expansionBindDepth`, the single owner of this formula — it was
                // inlined here, which is how one quantity came to be spelled three ways across five resolvers.
                return contained.flatMap { field ->
                    comparedCells(
                        when (a) {
                            is Expr.Star -> Expr.Star(field, a.having, a.outer, a.bindDepth)
                            else -> Expr.Star(field, null, (a as Expr.Field).outer,
                                expansionBindDepth(path, -1))
                        },
                    )
                }
            }
        }
        return if (a is Expr.Star) filteredStarCells(a)                   // kept row values, empties already dropped
        else if (ctx.filled(pathOf(a))) listOf(evalExpr(a)) else emptyList()
    }

    /** The path of a plain field-ref operand. Any other operand shape in a field-ref position is a construct
     *  this evaluator does not model there — reported UNSUPPORTED (the per-rule tolerance catches it), never a
     *  ClassCastException that would sink the whole validation (the per-rule degrade net, IF61). */
    internal fun pathOf(e: Expr): String = (e as? Expr.Field)?.path
        ?: throw UnsupportedConstructException("unsupported operand ${e::class.simpleName} in a field-reference position")
}
