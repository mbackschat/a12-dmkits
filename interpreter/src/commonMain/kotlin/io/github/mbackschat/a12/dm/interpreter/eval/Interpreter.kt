package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.parentGroup
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.expansionBindDepth
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
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
 * customErrorField, invalid, outer)` — that this strategy core and its collaborator
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
     * Whether a referenced field is **formally invalid** — a present malformed value (a format error) OR a
     * required-but-empty field (a `mandatoryField` error). Such a field resolves to [Val.Unknown], so the
     * kernel's three-valued logic propagates it: a comparison goes UNKNOWN (does not fire), but `… Or <true>`
     * still fires and `… And <true>` stays UNKNOWN — the kernel's operand-level "not check-relevant" behavior,
     * NOT a whole-rule skip. Defaults to "always valid" (a bare low-level [Interpreter] with no formal pass);
     * [DmInterpreter] supplies it from the model.
     *
     * **The [EvalContext] argument is load-bearing and must be the one the neighbouring VALUE read uses.** The
     * gate is a per-CELL question, and a cell is a path plus the reps the reading context resolves for it — so
     * a `Having` filter row must judge ITS row's cell. The gate used to close over the enclosing rule row and
     * ignore the reading context, which made one malformed cell in the FIRST repetition suppress the filter
     * leaf of every other row and silently empty the aggregate (IF319; `HavingNonTrueRowDiffTest`). Its
     * neighbours in [filledTri] were already context-relative, which is what the shape now matches.
     */
    internal val invalid: (EvalContext, String) -> Boolean = { _, _ -> false },
    /** The complete ENCLOSING repetition context for `$` refs: set only while evaluating a `Having`
     *  filter. A marked ref resolves its own named path's coordinate from this environment, not one
     *  scalar index and not the inner filtered row. Null at top level. */
    private val outer: EvalContext? = null,
    /** Retained for source/binary constructor compatibility after KF80's binding correction. The former
     * unbound mode was incorrect; every Having filter now captures its enclosing row. */
    @Suppress("UNUSED_PARAMETER") outerUnbound: Boolean = false,
    /**
     * The per-row `RepetitionNotUnique` outcome for THIS rule (IF149) — `(row ctx) → VALUE / OMISSION / null`
     * (null = the row's key is not a duplicate). Read from the call-scoped duplicate cache
     * [io.github.mbackschat.a12.dm.interpreter.RepetitionNotUniqueEvaluator] builds ONCE per pass, so RNU is an
     * ordinary 3VL leaf composed by the boolean walk. Non-null only for a rule whose condition contains an RNU
     * leaf; a `RepetitionNotUnique` predicate reached with this null is reported unsupported (degrade-loud). Null
     * in sub-interpreters ([havingInterp]) — RNU cannot appear inside a `Having`.
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
        invalid: (EvalContext, String) -> Boolean,
        rnuOutcome: ((EvalContext) -> MsgType?)?,
        customFormallyIncorrect: () -> Set<CustomConditionFieldPointer>,
        customErrorIsGroup: Boolean,
    ) : this(ctx, config, customErrorField, invalid, rnuOutcome = rnuOutcome) {
        this.customFormallyIncorrect = customFormallyIncorrect
        this.customErrorIsGroup = customErrorIsGroup
    }

    /** Whether the rule's error entity is a group, which a reached custom leaf cannot be handed (KF615). */
    private var customErrorIsGroup = false

    private var customFormallyIncorrect: (() -> Set<CustomConditionFieldPointer>)? = null

    // A Having creates a row interpreter per filter read. Keep immutable call settings on EvalConfig:
    // the V8 allocation profile identified their repeated construction as material overhead (IF391).
    internal val clock get() = config.clock
    internal val emptyMayBeUnavailable get() = config.emptyMayBeUnavailable
    internal val baseYear get() = config.baseYear
    internal val timeZone get() = config.timeZone
    internal val indexFields get() = config.indexFields
    internal val rawStringFields get() = config.rawStringFields
    private val predefinedTypes get() = config.predefinedTypes
    private val customConditions get() = config.customConditions
    private val customFieldTypes get() = config.customFieldTypes
    internal val cellRelevant get() = config.cellRelevant
    internal val starRelevant get() = config.starRelevant
    internal val starExtentKnown get() = config.starExtentKnown
    internal val starFullyEnumerated get() = config.starFullyEnumerated
    internal val groupCountGate get() = config.groupCountGate
    private val absentGroupsCovered get() = config.absentGroupsCovered
    private val customRelevant get() = config.customRelevant
    private val computeScanState get() = config.computeScanState
    internal val computeMode get() = config.computeMode
    internal val filterWalkGroups get() = config.filterWalkGroups

    /** Validation-call-local scope results, installed only by [ValidationPass]. A direct low-level
     * [Interpreter] and every compute interpreter deliberately keep this null, so reusing an [EvalConfig] across
     * documents cannot carry values between calls and a mutable computation overlay is never cached. */
    internal var scopeMemo: ScopeMemo? = null
        private set

    internal fun sharingScopeMemo(memo: ScopeMemo): Interpreter = apply {
        scopeMemo = memo
    }

    /** A scoped [IndexColumn] resolver. Validation owns relevance-filtered columns for one call because a
     *  [Document] outlives that relevance set; computation owns derived columns for one overlay step because
     *  the next step may write a column the earlier one read. See [indexColumnOf]. */
    private var indexColumnMemo: ((Pair<String, List<Int>>, () -> IndexColumn) -> IndexColumn)? = null

    internal fun sharingIndexColumns(resolve: (Pair<String, List<Int>>, () -> IndexColumn) -> IndexColumn): Interpreter = apply {
        indexColumnMemo = resolve
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
        if (c.presenceUnknown(path)) GroupPresenceState(filled = false, erroneous = false, GroupRelevance.NONE)
        else config.groupPresence?.invoke(path, c.repsOf(path)) ?: GroupPresenceState(c.groupFilled(path), erroneous = false)

    /** The presence of a keyed group no row carries, asked at its unbound coordinate [repetitions]: empty, and
     *  decidably empty only where the validation coverage reaches that coordinate. Outside partial validation
     *  every coordinate is fully relevant, so this is the plain empty group. */
    internal fun absentGroupPresence(path: String, repetitions: List<Int>): GroupPresenceState =
        config.groupPresence?.invoke(path, repetitions) ?: GroupPresenceState(filled = false, erroneous = false)

    /** The presence of a GROUP operand, bare or keyed. A keyed group reads the row its key selects, a key no row
     *  carries reads as an empty group at the unbound row, and an undecidable key is null, which every consumer
     *  reads as UNKNOWN (IF331). A keyed FIELD is not a group operand: the quantifiers read it as a cell before
     *  asking here, and the leaves, which A12 refuses it in (`MVK_NO_FIELDS_ALLOWED`), meet [pathOf]'s refusal. */
    private fun groupOperandPresence(arg: Expr): GroupPresenceState? =
        if (isKeyedGroup(arg)) when (val selection = keyedGroupSelection(arg as Expr.SemanticIndex)) {
            is KeyedGroupSelection.Row -> groupPresence(selection.ctx, arg.path)
            is KeyedGroupSelection.Absent -> absentGroupPresence(arg.path, selection.repetitions)
            KeyedGroupSelection.Unavailable -> null
        } else groupPresence(ctxOf(arg), pathOf(arg))

    /** The context a node resolves against: the complete enclosing environment for `$`, else [ctx].
     *  Path-specific resolution within that context selects the marked node's named coordinate. */
    internal fun ctxOf(node: Expr): EvalContext = when {
        node is Expr.Field && node.outer -> outerOrThrow()
        node is Expr.Star && node.outer -> outerOrThrow()
        node is Expr.EntitySpec && node.source.outer -> outerOrThrow()
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

    private fun outerOrThrow(): EvalContext =
        outer ?: throw UnsupportedConstructException("a \$ outer-correlation reference is only valid inside a Having filter")

    /** A sub-interpreter for a `Having` candidate [rc], carrying the SAME config and capturing the
     *  enclosing iteration's complete [ctx] environment for path-specific `$` resolution. */
    internal fun havingInterp(rc: EvalContext): Interpreter =
        Interpreter(rc, config, customErrorField, invalid, outer = ctx).also {
            it.scopeMemo = scopeMemo
            it.indexColumnMemo = indexColumnMemo
        }

    /** A sub-interpreter rooted at the row [rc] a keyed group operand selects, carrying the SAME config, gates and
     *  `$` environment, so a bare-group consumer arm asked of it reads that row (IF340). It leaves the scope
     *  memo unset, which only costs a recomputation. */
    internal fun selectedRowInterp(rc: EvalContext): Interpreter =
        Interpreter(rc, config, customErrorField, invalid, outer).also {
            it.indexColumnMemo = indexColumnMemo
        }

    fun fires(cond: Cond): Boolean = eval(cond) == Tri.TRUE

    /**
     * Whether a `Having` filter keeps this candidate row. The verdict is [fires]', but the walk is the kernel's
     * generated filter code, which short-circuits on known truth in validation as in computation: an `And` stops
     * after a not-true left and an `Or` after a true one, so a later leaf that would fail at runtime
     * ([TemporalLabelFailure]) is not reached (SPEC-2026-10-07-04, `adapter.laws.FilterTemporalLiteralFailureDiffTest`).
     */
    internal fun keeps(filter: Cond): Boolean = gated(filter) && knownTrue(filter)

    /**
     * Evaluate a value expression (a computation's RHS) to a typed [Val] — NOT gated by the row's
     * `evaluated` flag, since a computation computes even on an otherwise-empty row (an empty numeric
     * operand reads as 0, so `[BaseFee]` over no base fee yields 0, not nothing).
     */
    fun evalExpression(expr: Expr): Val = evalExpr(expr)

    /**
     * As [evalExpression], but told the DECLARED KIND — and, for a DATE, the declared FORMAT — of the target the
     * value is being assigned to. A bare quoted date literal is typed by that kind, and a year-omitting one needs
     * the format to decide whether a reference year is observable at all — see [evalAssignedExpr]. Every other
     * expression evaluates identically, so a caller with no target may keep the single-argument form.
     */
    fun evalExpression(expr: Expr, targetKind: Kind?, targetDateFormat: String? = null): Val =
        evalAssignedExpr(expr, targetKind, targetDateFormat)

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

    /**
     * The kernel's two-pass rule evaluation, for a condition whose reached leaves are observable because one can
     * fail at runtime ([RuntimeEvaluationFailure]). The first pass asks only whether the condition is KNOWN TRUE, in
     * authored order with both connectives short-circuited — `And` stops after a not-true left, `Or` after a true
     * one — which fires on exactly the rows the three-valued walk fires on but reaches fewer leaves. A true rule
     * whose constructs enable classification ([classifies], [classificationEnabled]) then re-evaluates every leaf
     * eagerly to type the message; without classification the kernel emits a VALUE message (SPEC-2026-10-03-06).
     */
    fun firedTypeInTwoPasses(cond: Cond, classifies: Boolean): MsgType? {
        if (!gated(cond)) return null
        if (!knownTrue(cond)) return null
        return if (classifies) evalTyped(cond).type else MsgType.VALUE
    }

    private fun knownTrue(c: Cond): Boolean = when (c) {
        is Cond.And -> knownTrue(c.left) && knownTrue(c.right)
        is Cond.Or -> knownTrue(c.left) || knownTrue(c.right)
        else -> evalCond(c) == Tri.TRUE
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
     * row is evaluated only when the condition CAN fire empty ([Cond.canFireEmpty]) — so the negative presence
     * conditions (NoFieldFilled, NotAllFieldsFilled, FieldNotFilled, …) fire on a blank instance, while a
     * comparison's empty-as-0 stays suppressed (the kernel's structural "can fire empty" gate, not a data check).
     * In [partial] validation the gate is bypassed — the upstream relevance gate has already decided (see [partial]).
     *
     * A computation has no such gate: both kernel strategies decide a precondition on every instance they compute,
     * so `[Quantity] < 5` computes over an empty non-repeatable group, an absent one, and one whose only content was
     * a computed field's stored value (`adapter.laws.CascadeGroupPresenceDiffTest`, 2026-09-23). A repeatable row
     * never needed the bypass, because an instantiated row is content in itself; which instances exist at all is
     * decided before any condition is read ([Document.computeContexts]).
     */
    private fun gated(cond: Cond): Boolean = partial || computeMode || ctx.evaluated || cond.canFireEmpty()

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

    /** The call's work tally, reachable from the value-expression collaborator, which cannot see [config]. */
    internal val work: WorkLedger? get() = config.work

    private fun evalCond(c: Cond): Tri {
        // Every condition node of every rule passes through this dispatch, including the nodes a sub-interpreter
        // walks, so one increment here is the whole call's evaluation volume (WorkLedger).
        work?.conditionNode()
        return evalCondNode(c)
    }

    private fun evalCondNode(c: Cond): Tri = when (c) {
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
        if (customErrorIsGroup) throw GroupErrorEntityCustomFailure(c.name)
        val condition = customConditions[c.name]
            ?: throw UnsupportedConstructException("unregistered custom condition \"${c.name}\" — register it in the custom-condition registry")
        val errorField = customErrorField
            ?: throw UnsupportedConstructException("custom condition \"${c.name}\" needs the rule's error field — unavailable in this context")
        val data = ctx.customConditionData()
        // The model-aware DmInterpreter path supplies the exact call-level formal set. Retain the former
        // best-effort behavior for direct low-level Interpreter users, whose context has no ModelDef/formal pass.
        val formallyIncorrect = customFormallyIncorrect?.invoke()
            ?: data.fields().filter { invalid(ctx, it.path) }.toSet()
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
        val op = PredicateOp.byName(p.name) ?: throw UnsupportedConstructException("unsupported predicate ${p.name}")
        return when (op) {
            PredicateOp.FIELD_FILLED -> presenceTri(p.args.single())
            PredicateOp.FIELD_NOT_FILLED -> Kleene.not(presenceTri(p.args.single()))
            PredicateOp.GROUP_FILLED -> groupOperandPresence(p.args.single())?.let {
                if (it.relevance == GroupRelevance.NONE) Tri.UNKNOWN else Tri.of(it.filled)
            } ?: Tri.UNKNOWN
            PredicateOp.GROUP_NOT_FILLED -> groupOperandPresence(p.args.single())?.negativeLeaf ?: Tri.UNKNOWN
            PredicateOp.FIELD_VALUE_IN_LIST -> valueListMembership(p.args, included = true).truth
            PredicateOp.FIELD_VALUE_NOT_IN_LIST -> valueListMembership(p.args, included = false).truth
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
                else dateValidity(p.args.single(), expectValid = true)
            PredicateOp.INVALID -> if (p.args.size == 2) predefinedTypeValidity(p.args, expectValid = false)
                else dateValidity(p.args.single(), expectValid = false)
            // List quantifiers retain the row-evaluated gate upstream. Arbitrary full-validation filters use
            // deciding-prefix scans (KF512); ordinary and optimized correlations retain their operand tallies.
            // COMPUTE mode replaces the tally with the kernel's ordered SCAN so a read is order-observable and a
            // later invalid operand after the deciding one is never read (IF85): the FIELD-fill family scans cells
            // ([computeFillQuantifier]); the GROUP-fill family scans its common entity list ([computeGroupFillQuantifier],
            // IF219) — a FIELD operand READS its cell (an invalid one poisons), a bare or starred GROUP operand is
            // structural presence (never poison) and a keyed one poisons only on its key column (IF331), each
            // yielded lazily so the per-operator stop ends the scan.
            PredicateOp.ALL_FIELDS_FILLED, PredicateOp.NO_FIELD_FILLED, PredicateOp.AT_LEAST_ONE_FIELD_FILLED,
            PredicateOp.MORE_THAN_ONE_FIELD_FILLED, PredicateOp.NOT_ALL_FIELDS_FILLED,
            PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED, PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED,
            PredicateOp.ALL_GROUPS_FILLED, PredicateOp.NO_GROUP_FILLED, PredicateOp.AT_LEAST_ONE_GROUP_FILLED,
            PredicateOp.NOT_ALL_GROUPS_FILLED, PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED -> when {
                computeMode && !op.countsGroups -> computeFillQuantifier(op, p.args)
                computeMode && op.countsGroups -> computeGroupFillQuantifier(op, p.args)
                incrementalFill(op, p.args) -> filteredFillResult(op, p.args).truth
                !op.countsGroups && (hasFillScopeOperand(p.args) ||
                    (op in DECLARED_FILL_OPERATORS && p.args.any { it is Expr.Star && it.having != null })) -> Tri.of(fillScopeFires(op, fillScopeInfo(p.args)))
                else -> {
                    val (t, f, n) = quantCounts(op, p.args)
                    Tri.of(quantifierFires(op, t, f, n))
                }
            }
        }
    }

    /** A field-fill quantifier operand that needs the group/starred-scope treatment (IF49): a bare GROUP ref (a
     *  `Field` whose path is not a declared field), a keyed one (IF340), or a starred field/group WITHOUT a
     *  `Having` (a Star with a Having is the filtered-correlation path, handled by [starQuantTally]). A plain field
     *  list is not a scope. A mixed direct/filtered list uses the same operand expansion. */
    internal fun hasFillScopeOperand(args: List<Expr>): Boolean =
        (args.any { it !is Expr.Star } && args.any { it is Expr.Star && it.having != null }) || args.any {
        (it is Expr.Star && it.having == null) || (it is Expr.Field && ctxOf(it).declaredKind(it.path) == null) ||
            isKeyedGroup(it) || it.isIndexedStar()
    }

    /** Aggregate the [FillScope] tally across a fill quantifier's operands (a group scope expands to its
     *  descendant fields; the counts sum, the declared flags OR/AND). */
    internal fun fillScopeInfo(args: List<Expr>): FillScope {
        return combined(args.map { a -> if (a is Expr.SemanticIndex) keyedFillScope(a) else operandFillScope(a) })
    }

    /** One fill scope over several operands' cells: the tallies add, and the declared range is all of theirs. */
    private fun combined(scopes: List<FillScope>): FillScope =
        FillScope(scopes.sumOf { it.tInst }, scopes.sumOf { it.fInst }, scopes.sumOf { it.nInst },
            scopes.any { it.hasUnknownInst }) {
            DeclaredFill(scopes.all { it.allFilledDeclared }, scopes.any { it.anyEmptyDeclared })
        }

    /** The [FillScope] of one bare, starred or filtered operand of a fill quantifier's list. */
    private fun operandFillScope(a: Expr): FillScope {
        if (a.isIndexedStar()) return withIndexedStar(a as Expr.EntitySpec, cellFillScope(Tri.UNKNOWN)) { operandFillScope(it) }
        val path = scopePathOf(a)
        val c = ctxOf(a)
        return when {
            // A starred index-field operand's instantiated tally is index-aware (mandatory-empty / duplicated
            // cells are not-check-relevant → the UNKNOWN bucket), so a fill quantifier over an index field matches
            // the kernel's `isWertAngegeben` (AtLeastOne stops firing on a fully-duplicated index; IF15/IF60).
            a is Expr.Star && a.having == null && a.path in indexFields -> indexStarFillScope(c, a.path)
            // A FILTERED operand must be tallied through its filter even here. [EvalContext.fillScope] takes
            // no `having` parameter at all, so before this branch the filter was silently dropped for every
            // operand of a list that any OTHER operand had diverted onto this path — see [filteredFillScope].
            a is Expr.Star && a.having != null -> filteredFillScope(c, a, a.having)
            // Under partial validation a GROUP, starred or not, is tallied field by field, as every field-expanding
            // consumer reads it, so each contained field's slots meet the relevant set (KF623 for the unstarred one).
            cellRelevant != null && (a is Expr.Star || a is Expr.Field && namesAGroup(a)) ->
                if (c.declaredKind(path) != null) relevantStarFillScope(c, a as Expr.Star)
                else combined(expandOneGroupOperand(a).map { operandFillScope(it) })
            // A STARRED operand carries its own binding level; an unstarred one has none, and `-1` then
            // means "cap at the operand's own path depth".
            a is Expr.Star && !a.outer -> memoizedFillScope(c, a)
            else -> plainFillScope(c, a, path)
        }
    }

    private fun plainFillScope(c: EvalContext, a: Expr, path: String): FillScope =
        c.fillScope(path, isGroup = c.declaredKind(path) == null,
            bindDepth = (a as? Expr.Star)?.bindDepth ?: -1, cellValue = availableCells(c))

    /** An unfiltered star's tally reads only its scope's cells, each judged available by its own coordinates, so
     *  every row of one scope shares it, as [scopeMemo]'s aggregates do; a per-row rule otherwise re-walks the
     *  whole scope once per row (IF411, `RowInvariantStarReaderPerfTest`). */
    private fun memoizedFillScope(c: EvalContext, a: Expr.Star): FillScope {
        // A filter or an outer read varies per row, so its tally is never shared; [invariantAggregateMemoKey] refuses the same.
        require(a.having == null && !a.outer) { "a filtered or outer star has no row-invariant fill scope: ${a.path}" }
        val memo = scopeMemo?.fillScopes ?: return plainFillScope(c, a, a.path)
        return memo.getOrPut(FillScopeMemoKey(a.path, a.bindDepth, starScopePrefix(c, a))) { plainFillScope(c, a, a.path) }
    }

    /** The coordinates every row of [star]'s scope shares in [c], which key a row-invariant read of it. */
    internal fun starScopePrefix(c: EvalContext, star: Expr.Star): List<Int> {
        val groupPath = if (c.declaredKind(star.path) == null) star.path else star.groupPath
        return c.scopePrefixOf(groupPath, star.bindDepth).toList()
    }

    /** The tally's cell source in [c]: an [unavailableEmpty] cell, a required-empty one above all, is undecided
     *  rather than empty, so it lands in neither bucket (KERNEL-FINDINGS KF552). A missing declared slot is
     *  never required and stays an ordinary empty. */
    private fun availableCells(c: EvalContext): CellValueSource = CellValueSource { path, reps, stored ->
        if (stored == null && unavailableEmptyAt(c, path, reps)) Val.Unknown else stored
    }

    /** [unavailableEmpty] for the cell at [path] and [reps] in [c]'s document, its row present or not. */
    internal fun unavailableEmptyAt(c: EvalContext, path: String, reps: List<Int>): Boolean =
        unavailableEmpty(c.absentGroupRow(path.substringBeforeLast('/'), reps.dropLast(1)), path)

    /**
     * A starred field list's fill tally under partial validation. A12 visits every DECLARED slot of the list, a
     * missing one included, and a slot outside the relevant set is undecided — counted like a malformed cell, in
     * neither the filled nor the empty bucket. So `NoFieldFilled` stays silent while any slot of its extent is
     * outside the selection, and `NotAllFieldsFilled` still fires on a relevant empty one. A stored cell is mapped
     * through [cellRelevant]; a missing slot needs no value read, so the extent's coverage stands for all of them
     * at once ([starFullyEnumerated], the same complete-enumeration question `SumOfProducts` asks), adding one
     * undecided slot when it fails (SPEC-2026-10-06-03, `adapter.laws.PartialFillWalkRelevanceDiffTest`).
     */
    private fun relevantStarFillScope(c: EvalContext, star: Expr.Star): FillScope {
        val relevant = checkNotNull(cellRelevant)
        val available = availableCells(c)
        val scope = c.fillScope(star.path, isGroup = false, bindDepth = star.bindDepth) { path, reps, stored ->
            if (relevant(path, reps)) available.valueOf(path, reps, stored) else Val.Unknown
        }
        if (starFullyEnumerated?.invoke(star.path, star.bindDepth, c.repsOf(star.path)) != false) return scope
        return FillScope(scope.tInst, scope.fInst, scope.nInst + 1, hasUnknownInst = true) {
            DeclaredFill(scope.allFilledDeclared, scope.anyEmptyDeclared)
        }
    }

    /**
     * A keyed operand's share of a fill scope. A keyed GROUP reads the cells of the row its key selects, through
     * the bare group's own arm asked in that row ([keyedGroupCells], IF340); a key no row carries answers as one
     * empty cell and an undecidable one as one undecided cell, which every tally reads as it reads the empty and
     * the undecided group. A keyed FIELD is the one cell it reads, as the plain-list tally reads it: this path
     * threw on it until a starred sibling first took such a list here (IF340's sibling row).
     */
    private fun keyedFillScope(e: Expr.SemanticIndex): FillScope =
        if (!isKeyedGroup(e)) cellFillScope(presenceTri(e))
        else when (val cells = keyedGroupCells(e)) {
            is KeyedGroupCells.Selected -> cells.interpreter.fillScopeInfo(listOf(Expr.Field(e.path)))
            KeyedGroupCells.Empty -> cellFillScope(Tri.FALSE)
            KeyedGroupCells.Undecided -> cellFillScope(Tri.UNKNOWN)
        }

    /** One cell's share of a fill scope: filled, empty, or undecided — counted in neither bucket, like a malformed
     *  cell, and neither completing the declared range nor leaving a gap in it. */
    private fun cellFillScope(state: Tri): FillScope = when (state) {
        Tri.TRUE -> FillScope(1, 0, 1, false, allFilledDeclared = true, anyEmptyDeclared = false)
        Tri.FALSE -> FillScope(0, 1, 1, false, allFilledDeclared = false, anyEmptyDeclared = true)
        Tri.UNKNOWN -> FillScope(0, 0, 1, true, allFilledDeclared = false, anyEmptyDeclared = false)
    }

    /** Selected declared slots determine All/NotAll; thresholds count selected instantiated cells. */
    private fun filteredFillScope(c: EvalContext, star: Expr.Star, having: Cond): FillScope {
        var t = 0
        var f = 0
        var n = 0
        var unknown = false
        for (rc in filterCandidates(c, star.groupPath, star.bindDepth)) {
            if (!havingInterp(rc).keeps(having)) continue
            n++
            when {
                rc.presenceUnknown(star.path) -> {}
                unavailableEmpty(rc, star.path) -> unknown = true
                !rc.filled(star.path) -> f++
                rc.field(star.path) is Val.Unknown -> unknown = true
                else -> t++
            }
        }
        return FillScope(t, f, n, unknown) {
            var allFilled = true
            var anyEmpty = false
            for (rc in c.declaredStarRows(star.path, star.bindDepth)) {
                if (!havingInterp(rc).keeps(having)) continue
                when {
                    rc.presenceUnknown(star.path) || unavailableEmpty(rc, star.path) -> allFilled = false
                    !rc.filled(star.path) -> { allFilled = false; anyEmpty = true }
                    rc.field(star.path) is Val.Unknown -> allFilled = false
                }
            }
            DeclaredFill(allFilled, anyEmpty)
        }
    }

    /** A filtered operand changes a positive threshold's polarity only if it supplies a deciding witness. */
    internal fun filteredFillType(op: PredicateOp, args: List<Expr>): MsgType {
        if (args.size == 1) return MsgType.OMISSION
        val threshold = when (op) {
            PredicateOp.AT_LEAST_ONE_FIELD_FILLED -> 1
            PredicateOp.MORE_THAN_ONE_FIELD_FILLED, PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED -> 2
            else -> return MsgType.OMISSION
        }
        var filled = 0
        var filteredWitness = false
        for (arg in args) {
            val count = if (arg is Expr.SemanticIndex) keyedFillScope(arg).tInst else operandFillScope(arg).tInst
            if (count > 0 && arg is Expr.Star && arg.having != null) filteredWitness = true
            filled += count
            if (filled >= threshold) return if (filteredWitness) MsgType.OMISSION else MsgType.VALUE
        }
        return MsgType.OMISSION
    }

    /** Whether a field-fill quantifier fires over a group/starred scope — `AllFieldsFilled`/`NotAllFieldsFilled`
     *  use the DECLARED range, `FieldsNotCollectivelyFilled` mixes, the rest use the instantiated tally (IF49). */
    private fun fillScopeFires(op: PredicateOp, fs: FillScope): Boolean = when (op) {
        PredicateOp.ALL_FIELDS_FILLED -> fs.allFilledDeclared
        PredicateOp.NOT_ALL_FIELDS_FILLED -> fs.anyEmptyDeclared
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED -> fs.tInst >= 1 && fs.anyEmptyDeclared
        else -> quantifierFires(op, fs.tInst, fs.fInst, fs.nInst)
    }

    private fun scopePathOf(e: Expr): String =
        (e as? Expr.EntitySource)?.path ?: throw UnsupportedConstructException("not a fill-scope operand: $e")

    /** SPEC-2026-10-10-02: arbitrary filters can fail after a deciding prefix; safe correlation
     * plans retain their indexed tally and partial validation retains its relevance-owned gates. */
    internal fun incrementalFill(op: PredicateOp, args: List<Expr>): Boolean =
        !computeMode && cellRelevant == null && op in FIELD_FILL_OPERATORS && args.any {
            it is Expr.Star && it.having != null && correlationStrategy(it) == CorrelationStrategy.NESTED
        }

    private enum class FillPass(val declared: Boolean = false, val traversal: FilterTraversal = FilterTraversal.DENSE) {
        ALL(declared = true), NONE, ANY(traversal = FilterTraversal.SPARSE), SECOND, EMPTY(declared = true);
    }

    /** A pass owns its cursor; composite predicates restart from operand one (SPEC-2026-10-10-02).
     * Truth and reached-filter polarity travel together so typing cannot drain a skipped suffix. */
    internal fun filteredFillResult(op: PredicateOp, args: List<Expr>): Res = when (op) {
        PredicateOp.ALL_FIELDS_FILLED -> filteredFillPass(args, FillPass.ALL)
        PredicateOp.NO_FIELD_FILLED -> filteredFillPass(args, FillPass.NONE)
        PredicateOp.AT_LEAST_ONE_FIELD_FILLED -> filteredFillPass(args, FillPass.ANY)
        PredicateOp.MORE_THAN_ONE_FIELD_FILLED -> filteredFillPass(args, FillPass.SECOND)
        PredicateOp.NOT_ALL_FIELDS_FILLED -> filteredFillPass(args, FillPass.EMPTY)
        PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED -> filteredFillPass(args, FillPass.NONE).let {
            if (it.truth == Tri.TRUE) it else filteredFillPass(args, FillPass.SECOND)
        }
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED -> filteredFillPass(args, FillPass.ANY).let {
            if (it.truth == Tri.TRUE) filteredFillPass(args, FillPass.EMPTY) else it
        }
        else -> throw UnsupportedConstructException("not a field-fill quantifier: $op")
    }

    private fun filteredFillPass(args: List<Expr>, pass: FillPass): Res {
        var filled = 0
        var filteredWitness = false
        fun observe(t: Int, f: Int, n: Int, filtered: Boolean): Res? {
            filled += t
            if (filtered && t > 0) filteredWitness = true
            val decided = when (pass) {
                FillPass.ALL -> t != n
                FillPass.NONE -> f != n
                FillPass.ANY -> filled > 0
                FillPass.SECOND -> filled > 1
                FillPass.EMPTY -> f > 0
            }
            if (!decided) return null
            val positive = pass == FillPass.ANY || pass == FillPass.SECOND
            return Res.of(Tri.of(pass != FillPass.ALL && pass != FillPass.NONE),
                if (!positive || filteredWitness) MsgType.OMISSION else MsgType.VALUE)
        }
        for (arg in args) {
            if (arg is Expr.Star && arg.having != null) {
                val c = ctxOf(arg)
                val rows = if (pass.declared)
                    keptRowsWithSuccessorLookahead(c.declaredStarRows(arg.path, arg.bindDepth).toList(), arg.having)
                else filteredRows(c, arg, pass.traversal)
                for (row in rows) {
                    val state = when {
                        row.presenceUnknown(arg.path) || unavailableEmpty(row, arg.path) -> Tri.UNKNOWN
                        !row.filled(arg.path) -> Tri.FALSE
                        row.field(arg.path) is Val.Unknown -> Tri.UNKNOWN
                        else -> Tri.TRUE
                    }
                    observe(if (state == Tri.TRUE) 1 else 0, if (state == Tri.FALSE) 1 else 0, 1, true)?.let { return it }
                }
            } else {
                val scope = if (arg is Expr.SemanticIndex) keyedFillScope(arg) else operandFillScope(arg)
                val decision = when (pass) {
                    FillPass.ALL -> observe(if (scope.allFilledDeclared) 1 else 0, 0, 1, false)
                    FillPass.EMPTY -> observe(0, if (scope.anyEmptyDeclared) 1 else 0, 1, false)
                    else -> observe(scope.tInst, scope.fInst, scope.nInst, false)
                }
                decision?.let { return it }
            }
        }
        return Res.of(Tri.of(pass == FillPass.ALL || pass == FillPass.NONE), MsgType.OMISSION)
    }

    // ---- COMPUTE-mode fill-quantifier scans (IF85 — the kernel's RuntimeController read order) --------------

    /**
     * COMPUTE-mode field-fill quantifiers — ordered scans with read poisoning: each visited
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
        PredicateOp.AT_LEAST_ONE_FIELD_FILLED -> Tri.of(scanFilled(args, FillPass.ANY).any { it })
        PredicateOp.NO_FIELD_FILLED -> Tri.of(scanFilled(args, FillPass.NONE).none { it })
        PredicateOp.ALL_FIELDS_FILLED -> Tri.of(scanFilled(args, FillPass.ALL).all { it })
        PredicateOp.NOT_ALL_FIELDS_FILLED -> Tri.of(notAllFilled(args))
        PredicateOp.MORE_THAN_ONE_FIELD_FILLED -> Tri.of(moreThanOneFilled(args))
        // The kernel's two-pass composition: TRUE when NONE is filled, else the more-than-one scan decides.
        PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED ->
            if (scanFilled(args, FillPass.NONE).none { it }) Tri.TRUE else Tri.of(moreThanOneFilled(args))
        // at-least-one filled AND not-all filled (kernel `entitiesNichtGemeinsamAngegeben`, two passes).
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED ->
            if (scanFilled(args, FillPass.ANY).none { it }) Tri.FALSE else Tri.of(notAllFilled(args))
        else -> throw UnsupportedConstructException("not a field-fill quantifier: $op")
    }

    /** The second-filled scan, which stops at the second filled cell. */
    private fun moreThanOneFilled(args: List<Expr>): Boolean {
        var seen = 0
        return scanFilled(args, FillPass.SECOND).any { it && ++seen > 1 }
    }

    private fun notAllFilled(args: List<Expr>): Boolean = !scanFilled(args, FillPass.ALL).all { it }

    /**
     * The lazy filled/empty cell stream of a fill quantifier's operands, in the kernel's read order — operands
     * in list order, a star's rows in row order, a bare group's fields in declaration order with every field's
     * declared repetitions interleaved before the next field, and a `Having` filter evaluated per row inside the
     * scan (its reads can poison too). An INVALID visited cell throws ([PoisonedReadException]); an uninstantiated
     * declared repetition reads empty without a value read, so it cannot poison.
     */
    private fun scanFilled(args: List<Expr>, pass: FillPass): Sequence<Boolean> = sequence {
        // A starred GROUP expands here through the SHARED owner, which is the point rather than a convenience.
        // The bare-group arm below already expanded and the starred one did not, so this operator was correct on
        // the validation path — which dispatches on `fillScope(isGroup = …)` — and read ZERO cells on the compute
        // path. A mode split is the worst member of the class: the operator is demonstrably right wherever the
        // estate pairs it and wrong on the path nobody paired. Measured on both kernel strategies with every cell
        // filled, `NoFieldFilled(Charges*)` answered TRUE here and `AtLeastOneFieldFilled(Charges*)` answered
        // FALSE — opposite directions, which is the signature of a quantifier over an EMPTY selection rather than
        // of a wrong verdict (IF287's class, seventh instance).
        for (a in expandGroupStars(args)) when {
            a.isIndexedStar() -> yieldAll(withIndexedStar(a as Expr.EntitySpec, sequenceOf(false)) {
                scanFilled(listOf(it), pass)
            })
            a is Expr.Star && a.having == null -> {
                val c = ctxOf(a)
                for (cell in c.starScanCells(a.path, a.bindDepth, pass.declared)) yield(availableFilledOrPoison(c, cell))
            }
            a is Expr.Star -> {
                // The declared-range scans select among EVERY declared coordinate, a missing one reading empty
                // without a value read, exactly as validation does ([KF512]); the thresholds search the stored rows.
                // Both read one kept successor ahead of the candidate they consume ([KF105], SPEC-2026-10-04-05),
                // and each consumed candidate is a read under the call-local formal oracle that can poison.
                val c = ctxOf(a)
                val rows = if (pass.declared)
                    keptRowsWithSuccessorLookahead(c.declaredStarRows(a.path, a.bindDepth).toList(), a.having!!)
                else filteredRows(c, a, pass.traversal)
                for (rc in rows) yield(availableFilledOrPoison(c, scanCell(a.path, rc)))
            }
            // A keyed GROUP scans the selected row as the bare group arm below scans its instance (IF340). The
            // selection itself poisons on a compromised key column; a key no row carries has only empty cells,
            // read without a value read, so nothing past it can poison.
            isKeyedGroup(a) -> when (val cells = keyedGroupCells(a as Expr.SemanticIndex)) {
                is KeyedGroupCells.Selected ->
                    yieldAll(cells.interpreter.scanFilled(listOf(Expr.Field(a.path)), pass))
                KeyedGroupCells.Empty, KeyedGroupCells.Undecided -> yield(false)
            }
            a is Expr.Field && ctxOf(a).declaredKind(a.path) == null -> {
                // A GROUP scope: scan its contained fields in DECLARATION order, each a lazy filled/empty read that
                // poisons on a malformed cell — so an operator's early-stop skips a later invalid cell, the kernel's
                // ordered getAllContainedFields scan (IF147). Was an eager whole-group tally that poisoned on ANY
                // unknown regardless of a preceding decisive cell (the IF147 over-poison, probe-confirmed).
                val c = ctxOf(a)
                for (cell in c.containedFieldCells(a.path, pass.declared)) yield(availableFilledOrPoison(c, cell))
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

    internal fun scanCell(path: String, ctx: EvalContext): ScanCell {
        val state = ctx.cellState(path)
        return ScanCell(
            path,
            ctx.repsOf(path),
            ctx.displayValue(path),
            state,
            if (state == CellState.EMPTY) null else ctx.field(path),
            computed = state == CellState.FILLED && ctx.holdsComputedValue(path),
        )
    }

    /**
     * [filledOrPoison] for a scanned cell whose emptiness may be unavailable: in a computation only the empty index
     * cell the preliminary index check invalidates is, and it poisons the quantifier as it poisons the count
     * (KERNEL-FINDINGS KF552), so only an index cell is asked. A missing declared slot is never unavailable.
     */
    private fun availableFilledOrPoison(c: EvalContext, cell: ScanCell): Boolean {
        if (cell.baseState == CellState.EMPTY && cell.path in indexFields && unavailableEmptyAt(c, cell.path, cell.reps))
            throw PoisonedReadException(cell.path)
        return filledOrPoison(cell)
    }

    internal fun filledOrPoison(cell: ScanCell): Boolean = when (
        if (cell.baseState == CellState.FILLED) computeScanState?.invoke(cell) ?: cell.baseState else cell.baseState
    ) {
        CellState.INVALID -> throw PoisonedReadException(cell.path)
        CellState.FILLED -> true
        CellState.EMPTY -> false
    }

    /** `Valid(Date(...))` when [expectValid], else `Invalid(Date(...))`: complements, but a non-evaluable date is UNKNOWN to both. */
    private fun dateValidity(arg: Expr, expectValid: Boolean): Tri = when (dateStatusOf(arg)) {
        DateStatus.REAL -> Tri.of(expectValid)
        DateStatus.UNREAL_PRESENT, DateStatus.HAS_EMPTY -> Tri.of(!expectValid)
        DateStatus.NON_RELEVANT, DateStatus.MALFORMED -> Tri.UNKNOWN
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
     * check on a malformed field is not-check-relevant even though it reads no value. (KERNEL-FINDINGS §3.) In a
     * computation the [invalid] gate throws instead, so the presence check of such a cell is the poisoning read
     * (IF85), and a cascade-poisoned computed cell throws already at [EvalContext.filled].
     */
    internal fun filledTri(c: EvalContext, path: String): Tri = when {
        c.presenceUnknown(path) -> Tri.UNKNOWN   // presence itself is 3VL (invalid parallel index) — before filled
        // PARTIAL validation: a non-relevant cell is unavailable in BOTH directions — the kernel's
        // `isWertAngegeben` decides relevance before the value read, so an EMPTY non-relevant operand
        // is not an empty witness (`NoFieldFilled`/`NotAllGroupsFilled`/… stay silent over it). Checked
        // BEFORE the fill read; row-correct via the context's own reps. Null outside partial.
        cellRelevant?.invoke(path, c.repsOf(path)) == false -> Tri.UNKNOWN
        // An empty cell is a witness only while it is check-relevant: a required-empty field in validation, and an
        // empty index cell the compute path's preliminary check invalidates, are unavailable like a malformed one
        // (KERNEL-FINDINGS KF552, `adapter.laws.RequiredEmptyReadDiffTest`).
        !c.filled(path) -> if (invalid(c, path)) Tri.UNKNOWN else Tri.FALSE
        invalid(c, path) -> Tri.UNKNOWN
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
     *  - a bare GROUP contributes the IF193 presence product [GroupPresenceState.fillProduct], the same
     *    projection the scalar `GroupNotFilled` leaf complements — structural in compute (`erroneous`=false, so
     *    a malformed-only group is FILLED, never poison);
     *  - a STARRED GROUP scope (`NoGroupFilled`/`AtLeastOneGroupFilled`, the only forms the kernel lets carry a
     *    `*`) yields one state per selected environment: a repeatable terminal row is FILLED structurally
     *    (IF46/IF68), while a non-repeatable terminal below starred ancestors retains its ordinary group-product
     *    state (SPEC-2026-07-26-02). Partial validation also yields UNKNOWN when a missing declared position
     *    is not fully covered, after the held states so a positive witness remains decisive (SPEC-2026-10-10-20/21);
     *  - a KEYED FIELD (`Field For key`) contributes the presence of the cell its key selects, as `FieldFilled`
     *    reads it;
     *  - a KEYED GROUP (`Group For key`) contributes the product of the row its key selects; no row reads as an
     *    empty group at the unbound row, so FALSE only where the coverage reaches that row (IF334), and an
     *    undecidable key — a duplicated one, say — reads UNKNOWN, or poisons in compute;
     * Yielded lazily so a compute-mode consumer that decides early never reads (and never poisons on) a later
     * operand — the kernel's ordered `…EntityAngegeben` read.
     */
    internal fun groupFillStates(args: List<Expr>): Sequence<Tri> = sequence {
        for (arg in args) when {
            arg.isIndexedStar() -> yieldAll(withIndexedStar(arg as Expr.EntitySpec, sequenceOf(Tri.UNKNOWN)) {
                groupFillStates(listOf(it))
            })
            arg is Expr.Star -> {
                val context = ctxOf(arg)
                val present = absentGroupsCovered?.let { HashSet<List<Int>>() }
                for (rc in context.groupListEnvironments(arg.path, arg.bindDepth)) {
                    if (arg.having != null && !havingInterp(rc).keeps(arg.having)) continue
                    present?.add(rc.repsOf(arg.path))
                    yield(groupPresence(rc, arg.path).fillProduct)
                }
                if (present != null && absentGroupsCovered?.invoke(
                        arg.path, arg.bindDepth, context.repsOf(arg.path), present) == false) yield(Tri.UNKNOWN)
            }
            // a keyed operand names its target by path, so a keyed FIELD reads its selected cell like `FieldFilled`
            ctxOf(arg).declaredKind(if (arg is Expr.SemanticIndex) arg.path else pathOf(arg)) != null ->
                yield(presenceTri(arg))
            else -> yield(groupOperandPresence(arg)?.fillProduct ?: Tri.UNKNOWN)
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
        // MORE THAN ONE operand with a star among them: each contributes its OWN cells and the tally is their
        // union — measured on both kernel strategies (`TwoFilteredStarsQuantifierDiffTest`), where two filtered
        // stars each keep their own rows and neither filter reaches the other side. `singleOrNull` sent this list
        // to `quantTally`, which reads every operand as a plain field reference and throws on the first `Star`,
        // so the kernel produced a verdict where this evaluator produced nothing (IG136's capability half).
        if (args.any { it is Expr.Star }) {
            var t = 0
            var f = 0
            var n = 0
            for (a in args) {
                if (a is Expr.Star) {
                    val (st, sf, sn) = starQuantTally(a)
                    t += st
                    f += sf
                    n += sn
                } else {
                    when (presenceTri(a)) {
                        Tri.TRUE -> t++
                        Tri.FALSE -> f++
                        Tri.UNKNOWN -> {}
                    }
                    n++
                }
            }
            return Triple(t, f, n)
        }
        val (t, f) = quantTally(args)
        return Triple(t, f, args.size)
    }

    /**
     * COMPUTE-mode group-list quantifiers — the kernel's ordered read of the common entity list, not validation's
     * tally: [groupFillStates] is consumed lazily and each operator STOPS at its deciding operand, so a malformed
     * FIELD operand AFTER the decision is never read and cannot poison (a filled/empty operand before it decided
     * first) — the read-order divergence IF219 fixed. A FIELD operand read is poison-capable; a GROUP operand
     * (bare or starred) is structural (never poison), and a keyed one poisons where its key column cannot
     * resolve (IF331). Mirrors [computeFillQuantifier] for the field-fill family
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
        val group = star.groupPath
        // The indexed plans read only stored rows, while the reference scan also walks missing coordinates. A kept
        // missing coordinate is an empty cell, adding to `f` and `n` alike. A filtered list reaches this tally only
        // for the operators that read `t` or `f == n`; the ones reading `t == n` or `f >= 1` take the declared fill
        // scope ([DECLARED_FILL_OPERATORS]). So the verdicts agree.
        return correlationPlan(star)?.let { indexedTally(star, group, it) } ?: naiveStarQuantTally(star, group)
    }

    /** The indexed `(t, f, n)` of [star] under its keyed or ordered [plan]. */
    private fun indexedTally(star: Expr.Star, group: String, plan: CorrelationPlan): Triple<Int, Int, Int> = when (plan) {
        is KeyedCorrelationPlan -> hashJoinTally(star, group, plan, unavailableRows(star, plan))
        is OrderedCorrelationPlan -> orderedCorrelationTally(star, group, plan, unavailableRows(star, plan))
    }

    /**
     * Runtime-qualified correlation plan. A multi-star self-exclusion stays general because it excludes every row
     * sharing the outer leaf index, not one physical row. Ordered plans are limited to kinds whose runtime order is
     * exactly [ValCompare.language]; non-UTC DATE_TIME needs instant ordering and therefore stays on the fallback.
     */
    private fun correlationPlan(star: Expr.Star): CorrelationPlan? {
        val shape = config.correlationShape(star)
        val plan = shape.plan ?: return null
        if (shape.multiStar && plan.selfExcluded) return null
        if (plan is OrderedCorrelationPlan && !orderedCorrelationSupported(plan)) return null
        return plan
    }

    /**
     * The scope's rows holding an [unavailableEmpty] cell in the starred column or a join field. The indexed tallies
     * count such a cell as an empty one, as a value or a key, while A12 leaves it undecided: no row is kept on an
     * unavailable join value, and a kept row's unavailable cell is neither filled nor empty (KERNEL-FINDINGS KF552).
     * So the indexed tally leaves these rows to [keptTally], each outer row paying for them alone: O(rows × these
     * rows) where A12 pays O(rows²) (INTERPRETER-FINDINGS IF423). Validation only: a computation's gate throws the
     * poison itself, and probing it here would read the column ahead of the operator. Fields none of whose empty
     * cells can be unavailable skip the walk, which tripled the multi-star correlation gate's allocation
     * (`docs/INTERPRETER-PERFORMANCE-RESULTS.md`, 2026-10-13).
     */
    private fun unavailableRows(star: Expr.Star, plan: CorrelationPlan): UnavailableRows {
        if (computeMode) return UnavailableRows.NONE
        val fields = columnsOf(star, plan).filter(emptyMayBeUnavailable)
        if (fields.isEmpty()) return UnavailableRows.NONE
        val group = star.groupPath
        val scan = { UnavailableRows(filterCandidates(ctx, group, star.bindDepth).filter { row -> fields.any { unavailableEmpty(row, it) } }) }
        // Only a validation call owns a memo; a low-level interpreter rescans, since its config may meet another document.
        val memo = scopeMemo ?: return scan()
        return memo.unavailableRows.getOrPut(listOf(star.path, plan.innerFields, star.bindDepth, ctx.repsOf(group).take(maxOf(star.bindDepth, 0))), scan)
    }

    /** Whether the iterated row is itself one of [unavailableRows]: the cells are its own under a self-exclusion. */
    private fun ownRowUnavailable(star: Expr.Star, plan: CorrelationPlan): Boolean =
        columnsOf(star, plan).any { emptyMayBeUnavailable(it) && unavailableEmpty(ctx, it) }

    /** The cells an indexed tally reads in each row: the starred column and the plan's join fields. */
    private fun columnsOf(star: Expr.Star, plan: CorrelationPlan): List<String> = listOf(star.path) + plan.innerFields

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
        return when (correlationPlan(star)) {
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
    private fun naiveStarQuantTally(star: Expr.Star, group: String): Triple<Int, Int, Int> =
        keptTally(star, starCandidateRows(ctx, star, group))

    /** The rows [star]'s filter judges in [c]: every row without a `Having`, else the filter's candidates. */
    internal fun starCandidateRows(c: EvalContext, star: Expr.Star,
            group: String = star.groupPath): List<EvalContext> =
        if (star.having == null) c.rowContexts(group, star.bindDepth) else filterCandidates(c, group, star.bindDepth)

    /** The `(t, f, n)` of the [rows] the star's filter keeps, each kept cell judged in three states. */
    private fun keptTally(star: Expr.Star, rows: List<EvalContext>): Triple<Int, Int, Int> {
        var t = 0
        var f = 0
        var n = 0
        for (rc in rows) {
            if (star.having != null && !havingInterp(rc).keeps(star.having)) continue
            n++
            when {
                rc.presenceUnknown(star.path) || unavailableEmpty(rc, star.path) -> {}
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
        val group = star.groupPath
        val fast = indexedTally(star, group, correlationPlan(star) ?: return null)
        return fast to naiveStarQuantTally(star, group)
    }

    /**
     * The `(t, f, n)` of a filtered quantifier whose `Having` is an equality self-correlation — computed by a
     * HASH JOIN (O(1) per outer row) instead of the O(N²) inner scan: look up the outer row's bucket by its
     * OUTER-field values, then remove the outer row's own contribution when a self-exclusion is present (it sits
     * in its own bucket iff its inner-field values equal its outer-field values). Returns exactly what the scan
     * would for well-formed data (the differential + fuzz lock it). Buckets are built + cached by the Document.
     * The [unavailable] rows are taken out of the bucket and tallied as the general scan tallies them.
     */
    private fun hashJoinTally(
        star: Expr.Star,
        group: String,
        join: KeyedCorrelationPlan,
        unavailable: UnavailableRows,
    ): Triple<Int, Int, Int> {
        val outerKey = join.outerFields.map { p -> gatedField(ctx, p) }
        if (outerKey.any { it is Val.Unknown }) return Triple(0, 0, 0)   // an Unknown outer value never matches
        var tally = ctx.correlationBuckets(group, join.innerFields, star.path, star.bindDepth)[outerKey].toTally()
        if (unavailable.rows.isNotEmpty()) {
            tally = tally - unavailable.buckets(join.innerFields, star.path)[outerKey].toTally() +
                keptTally(star, unavailable.rows)
        }
        if (join.selfExcluded && ctx.evaluated && !(unavailable.rows.isNotEmpty() && ownRowUnavailable(star, join))) {
            val outerInnerKey = join.innerFields.map { p -> gatedField(ctx, p) }
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
        unavailable: UnavailableRows,
    ): Triple<Int, Int, Int> {
        val outer = gatedField(ctx, plan.outerField)
        if (outer is Val.Unknown) return Triple(0, 0, 0)
        var tally = ctx.orderedCorrelation(group, plan.innerField, star.path, star.bindDepth).tally(plan.op, outer)
        if (unavailable.rows.isNotEmpty()) {
            tally = tally - unavailable.ordered(plan.innerField, star.path).tally(plan.op, outer) +
                keptTally(star, unavailable.rows)
        }
        if (plan.selfExcluded && ctx.evaluated && !(unavailable.rows.isNotEmpty() && ownRowUnavailable(star, plan))) {
            val inner = gatedField(ctx, plan.innerField)
            if (inner !is Val.Unknown && orderedComparisonMatches(plan.op, inner, outer)) {
                tally = subtractOwnContribution(star, tally)
            }
        }
        return tally
    }

    /** The value at [path] in [c], `Unknown` when the cell is not check-relevant ([invalid]). */
    internal fun gatedField(c: EvalContext, path: String): Val = if (invalid(c, path)) Val.Unknown else c.field(path)

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
     * `FieldValueIncludedInValueList(subject, m1, …)` and its `Not` form — truth and polarity in one pass, in both
     * phases (SPEC-2026-10-03-04). Equality is the typed `==` (string members compare as text, number members
     * numerically; the kernel refuses mixed types, `MVK_INVALID_TYPES_FOR_COMPARISON`).
     *
     * Every member is read before the verdict, so in computation a malformed member poisons even after a match or
     * beside an empty subject. A member field's empty cell is no member at all and its malformed cell is unknown
     * ([valueListCell]). An empty subject — a Number's included — holds no value, so NEITHER form holds on it; read as
     * zero it would match a zero member. A malformed subject leaves both forms unknown.
     *
     * Included holds when a present member equals the subject, and its firing is a value error. Not-included fails on
     * a matching member; otherwise an unknown member makes it unknown and an empty member makes its firing an
     * omission, since filling the member could clear it. The two kinds meet those members in a different order: a
     * Number subject walks the members and any unknown one decides, while a text or enumeration subject (the kernel's
     * string route) decides at the first empty or unknown member it meets, so an empty member before a malformed one
     * fires as an omission there and not for a Number. Locked by `MembershipComputationGuardDiffTest`.
     */
    internal fun valueListMembership(args: List<Expr>, included: Boolean): Res {
        val members = args.drop(1).map { valueListCell(it) }
        val subject = valueListCell(args[0])
        if (subject == null) return Res.of(Tri.FALSE, MsgType.VALUE)
        if (subject is Val.Unknown) return Res.of(Tri.UNKNOWN, MsgType.VALUE)
        val matched = members.any { it != null && it !is Val.Unknown && ValCompare.equal(subject, it) }
        if (included || matched) return Res.of(Tri.of(included && matched), MsgType.VALUE)
        if (subject is Val.Num) {
            if (members.any { it is Val.Unknown }) return Res.of(Tri.UNKNOWN, MsgType.VALUE)
            return Res.of(Tri.TRUE, if (members.any { it == null }) MsgType.OMISSION else MsgType.VALUE)
        }
        val decisive = members.indexOfFirst { it == null || it is Val.Unknown }
        return when {
            decisive < 0 -> Res.of(Tri.TRUE, MsgType.VALUE)
            members[decisive] == null -> Res.of(Tri.TRUE, MsgType.OMISSION)
            else -> Res.of(Tri.UNKNOWN, MsgType.VALUE)
        }
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
            for (cell in overlapCells(arg, FilterTraversal.SPARSE)) {
                if (rangesOverlap(scalar, cell))
                    return Res.of(Tri.TRUE, if (filtered) MsgType.OMISSION else MsgType.VALUE)
            }
        }
        return Res.of(Tri.FALSE, MsgType.VALUE)
    }

    /** Overlap drops empty/malformed ranges in validation and captures the enclosing row for `$` in a
     * filtered operand (OverlapFilterBindingDiffTest). Traversal and filter binding are independent. */
    private fun overlapCells(arg: Expr, traversal: FilterTraversal = FilterTraversal.DENSE): Sequence<Val.Range> = when {
        // A GROUP operand names no declared field, so BOTH arms below were blind to it and by different
        // mechanisms: the starred arm read `star` at the group path (zero cells, a silent non-overlap), while the
        // unstarred arm asked `evalExpr` for a range at a path holding no value and THREW `no kind declared` —
        // which the degrade net does not catch, so it sank the whole evaluation. `DateRangesOverlap` refuses a
        // group operand outright, but the plural form ADMITS one in its `In` slot ([KF187]), which is what made
        // this reachable and what corrected IF287's reason for leaving `filteredStarCells` unexpanded.
        // An EMPTY group contributes no range rather than recursing on itself. This used to read the expansion
        // owner's "yield the operand unchanged" fallback back out by IDENTITY (`expanded.singleOrNull() === arg`),
        // which coupled this site to that fallback's representation; the honest reason it stayed was that
        // `pathOf` REJECTS a `Star`, so there was no accessor to ask for the path here. `EntitySource.path` is
        // that accessor, so the emptiness question is now asked of the model directly.
        namesAGroup(arg) ->
            if (ctxOf(arg).containedFields((arg as Expr.EntitySource).path).isEmpty()) emptySequence()
            else expandOneGroupOperand(arg).asSequence().flatMap { overlapCells(it, traversal) }
        arg is Expr.Star -> filteredStarCells(arg, traversal).filterIsInstance<Val.Range>()
        else -> sequence { (evalExpr(arg) as? Val.Range)?.let { yield(it) } }
    }

    /** Overlap and uniqueness share filter binding, while computation retains poison-on-read and
     * validation retains its unknown values (KF80, OverlapFilterBindingDiffTest). */
    private fun filteredStarCells(star: Expr.Star, traversal: FilterTraversal = FilterTraversal.DENSE): Sequence<Val> {
        if (computeMode) return computeStarReads(star, listFilter(star), traversal).mapNotNull { it?.value }
        if (star.having == null) return ctxOf(star).star(star.path, star.bindDepth).asSequence()
        val rows = when (traversal) {
            FilterTraversal.DENSE -> keptRowContexts(star)
            FilterTraversal.SPARSE -> filteredRows(ctxOf(star), star, traversal, listFilter(star)).toList()
        }
        return rows.asSequence().mapNotNull { rc -> if (rc.filled(star.path)) rc.field(star.path) else null }
    }

    /** Both overlap forms and uniqueness capture the enclosing row for `$` (OverlapFilterBindingDiffTest).
     * Candidate traversal policy is independent of this binding. */
    private fun listFilter(star: Expr.Star): ((EvalContext) -> Boolean)? =
        star.having?.let { having -> { row: EvalContext -> havingInterp(row).keeps(having) } }

    /** `value PatternMatched/PatternViolated "regex"` — anchored full-value match; an empty pattern fails after the operand read. */
    internal fun evalPattern(p: Cond.Pattern): Tri {
        val v = evalExpr(p.value)
        val pattern = (p.pattern as Expr.StrLit).v
        if (pattern.isEmpty()) throw EmptyPatternFailure()
        if (v is Val.Unknown) return Tri.UNKNOWN
        val text = (v as? Val.Txt)?.v ?: error("pattern comparison needs a string value, got $v")
        val matched = config.patternMatches(pattern, text)
        return Tri.of(if (p.expectMatch) matched else !matched)
    }

    internal fun evalCmp(c: Cond.Cmp): Tri {
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
        // its exact instant — an ambiguous fall-back wall time re-parses at its earlier, summer-time offset, so the
        // equal-looking rendering of a standard-side chain result is a DIFFERENT instant (KERNEL-FINDINGS KF665). Under UTC the
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
        // order agree, and Berlin's history has exactly one ambiguous midnight (1916-10-01).
        if (l is Val.Txt && r is Val.Txt && isIsoDate(l.v) && isIsoDate(r.v)) {
            if (timeZone != ModelTimeZone.UTC) {
                val li = dateInstant(c.left)
                val ri = dateInstant(c.right)
                if (li != null && ri != null) return compareInstants(c.op, li, ri)
            }
            // Labels order lexically only while every year has four digits; a calendar shift can reach five
            // (SPEC-2026-10-04-01), so two dates compare by day number.
            val ld = epochDay(l.v)
            val rd = epochDay(r.v)
            if (ld != null && rd != null) return compareInstants(c.op, ld, rd)
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
     * Expansion stays with each operator: uniqueness admits groups (KF173), while overlap's two forms differ
     * in group admission (KF187). Their shared field reader must not choose that operator-specific contract.
     */
    private fun comparedCells(a: Expr): Sequence<Val> {
        keyedOperandCells(a) { comparedCells(it) }?.let { return it }
        // Routed through the SHARED owner rather than expanding here. This site used to carry a complete private
        // copy — `containedFields` + `Expr.Star` construction + recursion — with only the depth formula delegated,
        // so the "one owner" claim was false and, worse, the copy DIVERGED: its starred arm passed `bindDepth` raw
        // where the owner normalizes it through `ast.expansionBindDepth`. That difference is inert for parsed input
        // (whose bindDepth is always >= 0) and live for a hand-built `Expr.Star(path)`, which is exactly the input
        // the normalizer is kept for. A cold review caught both halves.
        //
        // What the owner preserves, and why each matters here: the star's `Having` and binding ride each expanded
        // field ([IF99] — the filter belongs to the authored operand, not to one of its expanded cells); BOTH arms
        // expand to a STARRED field, including the non-starred one, because a contained field may live under a
        // repeatable subgroup where a plain ref reads one ambient coordinate and silently drops every row but the
        // first; and the expanded field binds at the OPERAND'S OWN depth, so every level strictly below it stays
        // free whatever the rule iterates — the same rule the reference channel applies, since the two channels of
        // one operand must not disagree about its extent ([IF287]'s third and fourth instances).
        if (namesAGroup(a)) {
            val expanded = expandOneGroupOperand(a)
            if (expanded.singleOrNull() !== a) return expanded.asSequence().flatMap { comparedCells(it) }
        }
        // The fill test and the value read must ask the SAME context: `evalExpr` resolves through `ctxOf`, which
        // is the OUTER one for a `$`-marked node, so asking the ambient `ctx` here consulted a different row.
        // Identical to [IF287]'s fourteenth instance in `aggOperandVals`, one function over — measured with a
        // `$`-marked pair under `FieldValuesNotUnique` inside a filter (a shape the kernel admits): the marked
        // cells duplicate in the rule's own row and are empty in the row the filter keeps, so reading the
        // filtered row dropped both operands, found no duplicate, and the enclosing sum never cleared its bar.
        if (a is Expr.Star) {
            val c = ctxOf(a)
            // A duplicate hides later filters, after one kept successor (DenseUniquenessPrefixDiffTest).
            // Partial validation retains its separately measured row selection.
            if (!computeMode && a.having != null && cellRelevant == null) {
                return filteredRows(c, a, FilterTraversal.DENSE, listFilter(a)).mapNotNull { comparedRowValue(it, a.path) }
            }
            if (cellRelevant == null && c.datePrecisionOf(a.path) !in PARTIAL_PRECISIONS) return filteredStarCells(a)
            if (computeMode) return computeStarReads(a, listFilter(a)).mapNotNull { cell ->
                cell?.value?.let { resolved -> if (resolved is Val.Unknown) wellFormedPartialText(c, a.path, cell.raw) ?: resolved else resolved }
            }
            // Partial validation judges no extent here: a cell outside the coverage is skipped wherever it sits, so
            // the operator fires on a duplicate among the relevant cells alone (KERNEL-FINDINGS KF623).
            return keptRowContexts(a).asSequence()
                .filter { rc -> cellRelevant?.invoke(a.path, rc.repsOf(a.path)) != false }
                .mapNotNull { comparedRowValue(it, a.path) }
        }
        return sequence { if (ctxOf(a).filled(pathOf(a))) yield(comparedValue(ctxOf(a), pathOf(a), evalExpr(a))) }
    }

    /** The compared value of row [rc]'s cell at [path], or null when it is empty. */
    private fun comparedRowValue(rc: EvalContext, path: String): Val? =
        if (rc.filled(path)) comparedValue(rc, path, rc.field(path)) else null

    /**
     * The value `FieldValuesNotUnique` compares for one filled cell, given its [resolved] read. A partially known
     * Date resolves to [Val.Unknown] for every comparing reader, yet this operator compares the exact STORED TEXT
     * (KERNEL-FINDINGS KF260, which is why it alone of the comparing field-list operators admits one), so a
     * well-formed partial value contributes its text and `2024-06-00` beside a fully known `2024-06-01` is unique.
     * A malformed partial stays [Val.Unknown] and is skipped like any unconvertible cell. Measured at all three
     * precisions on named fields and at `DAY_OPTIONAL` down a starred column, both kernel strategies
     * (`adapter.laws.PartialDatePresenceDiffTest`).
     */
    private fun comparedValue(c: EvalContext, path: String, resolved: Val): Val {
        if (resolved !is Val.Unknown || c.datePrecisionOf(path) !in PARTIAL_PRECISIONS || invalid(c, path)) return resolved
        return wellFormedPartialText(c, path, c.displayValue(path)) ?: resolved
    }

    /** The stored text of a well-formed partial Date at [path], or null when [raw] is absent or not one. */
    private fun wellFormedPartialText(c: EvalContext, path: String, raw: String?): Val? {
        if (raw == null) return null
        val parts = partialDateParts(raw, c.dateFormatOf(path) ?: "yyyy-MM-dd", c.datePrecisionOf(path), timeZone)
        return if (parts != null) Val.Txt(raw) else null
    }

    /** The rows a starred operand keeps — every row when it carries no `Having`, else the rows its filter keeps. */
    private fun keptRowContexts(star: Expr.Star): List<EvalContext> {
        val rows = starCandidateRows(ctxOf(star), star)
        return if (star.having == null) rows else rows.filter { rc -> havingInterp(rc).keeps(star.having) }
    }

    /** The path of a plain field-ref operand. Any other operand shape in a field-ref position is a construct
     *  this evaluator does not model there — reported UNSUPPORTED (the per-rule tolerance catches it), never a
     *  ClassCastException that would sink the whole validation (the per-rule degrade net, IF61). */
    internal fun pathOf(e: Expr): String = (e as? Expr.Field)?.path
        ?: throw UnsupportedConstructException("unsupported operand ${e::class.simpleName} in a field-reference position")
}

/** The `DateType` precisions whose well-formed value may be partially known (a zero component). */
internal val PARTIAL_PRECISIONS = setOf(DatePrecision.DAY_OPTIONAL, DatePrecision.MONTH_OPTIONAL, DatePrecision.YEAR_OPTIONAL)
