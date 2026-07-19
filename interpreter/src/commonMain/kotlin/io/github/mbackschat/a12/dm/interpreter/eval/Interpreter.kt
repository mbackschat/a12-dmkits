package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.FieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.MsgType

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
    private val customErrorField: FieldPointer? = null,
    /**
     * Whether a referenced field is **formally invalid** in this context — a present malformed value (a
     * format error) OR a required-but-empty field (a `mandatoryField` error). Such a field resolves to
     * [Val.Unknown], so the kernel's three-valued logic propagates it: a comparison goes UNKNOWN (does not
     * fire), but `… Or <true>` still fires and `… And <true>` stays UNKNOWN — the kernel's operand-level
     * "not check-relevant" behavior, NOT a whole-rule skip. Defaults to "always valid" (the `compute` path,
     * which has no formal-error increment yet). [DmInterpreter] supplies it from the model + context.
     */
    internal val invalid: (String) -> Boolean = { false },
    /** The ENCLOSING iteration's context for `$` outer-correlation refs (KERNEL-FINDINGS §"$ correlation"):
     *  set only while evaluating a `Having` filter, so a `$`-marked ref inside the filter resolves against the
     *  row the aggregate/quantifier is iterating FROM, not the inner filtered row. Null at top level (a `$`
     *  there is an error — it needs an enclosing iteration). */
    private val outer: EvalContext? = null,
    /** The overlap-predicate `Having` filter (a plain boolean predicate's rep-list arg) has NO enclosing
     *  iteration to bind `$` to — unlike a quantifier's `Having`, whose star iterates. So here a `$`-marked
     *  ref is UNBOUND and resolves to UNKNOWN (the kernel then keeps the cell: keep-law = not-FALSE keeps),
     *  rather than binding to [outer] or throwing. Set only by [overlapFilterInterp] (KERNEL-FINDINGS §6, IF72). */
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
    private val customRelevant = config.customRelevant
    internal val computeMode = config.computeMode

    /** The context a node resolves against: the enclosing (`outer`) iteration for a `$`-marked ref, else [ctx]. */
    internal fun ctxOf(node: Expr): EvalContext = when {
        node is Expr.Field && node.outer -> outerOrThrow()
        node is Expr.Star && node.outer -> outerOrThrow()
        node is Expr.EnumCategory && node.outer -> outerOrThrow()
        else -> ctx
    }

    /** Whether a `$`-marked node is UNBOUND in this context — an overlap-predicate `Having` ([outerUnbound]) has
     *  no enclosing iteration, so a `$` ref there resolves to UNKNOWN (not [outer], not a throw). */
    internal fun outerUnknown(node: Expr): Boolean = outerUnbound && when (node) {
        is Expr.Field -> node.outer
        is Expr.Star -> node.outer
        is Expr.EnumCategory -> node.outer
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
        is Expr.EnumCategory -> e.outer
        is Expr.Func -> e.args.any { hasUnboundOuter(it) }
        is Expr.Arith -> hasUnboundOuter(e.left) || hasUnboundOuter(e.right)
        else -> false
    }

    /** A sub-interpreter for a `Having` filter row [rc], carrying the SAME config and setting the enclosing
     *  iteration ([ctx]) as its `outer` — so a `$`-marked ref in the filter resolves against this row. */
    internal fun havingInterp(rc: EvalContext): Interpreter =
        Interpreter(rc, config, customErrorField, invalid, outer = ctx)

    /** A sub-interpreter for the overlap predicates' `Having` filter row [rc] — like [havingInterp] but with NO
     *  outer binding ([outerUnbound]): a plain boolean predicate's rep-list arg has no enclosing iteration, so a
     *  `$` term in its filter is unbound → UNKNOWN → the cell is KEPT (keep-law: not-FALSE keeps; KERNEL-FINDINGS §6). */
    private fun overlapFilterInterp(rc: EvalContext): Interpreter =
        Interpreter(rc, config, customErrorField, invalid, outer = null, outerUnbound = true)

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
     *  eval when the rule's error-field instance is RELEVANT, and the kernel checks every relevant instance —
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
        is Cond.Custom -> false   // a custom condition is guarded by a field ref; an all-empty row is not evaluated
    }

    /** Whether a presence predicate is TRUE when all its operands are empty (k = 0 filled). */
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
        val col = c.indexColumn(path)
        return FillScope(col.cleanFilled, 0, col.cells.size, col.anyNotCheckRelevant,
            declared.allFilledDeclared, declared.anyEmptyDeclared)
    }

    /**
     * `left DiffersWithToleranceRangeN right` — fires iff `|left − right| > [Cond.Tolerance.tolerance]` (the
     * band is STRICT). Numbers only; an empty operand resolves to `Val.Num(0)` (empty-as-0, like any
     * comparison); a malformed / non-numeric operand → Unknown → the rule is suppressed. The band compare
     * rescales to scale 19, like the kernel's `vergleiche`.
     */
    internal fun evalTolerance(c: Cond.Tolerance): Tri {
        val a = (evalExpr(c.left) as? Val.Num)?.v ?: return Tri.UNKNOWN
        val b = (evalExpr(c.right) as? Val.Num)?.v ?: return Tri.UNKNOWN
        return Tri.of((a - b).abs().scale19().compareTo(c.tolerance.scale19()) > 0)
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
     * unsupported (fail-fast). Passes the row's data view + the rule's error field; [customRelevant] (null in
     * full validation, the relevant set in partial — IF38) and `formallyIncorrect` = the row's formally-invalid
     * fields, mirroring the kernel's call.
     */
    internal fun evalCustom(c: Cond.Custom): Tri {
        val condition = customConditions[c.name]
            ?: throw UnsupportedConstructException("unregistered custom condition \"${c.name}\" — register it in the custom-condition registry")
        val errorField = customErrorField
            ?: throw UnsupportedConstructException("custom condition \"${c.name}\" needs the rule's error field — unavailable in this context")
        val data = ctx.customConditionData()
        val formallyIncorrect = data.fields().filter { invalid(it.path) }.toSet()
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
            PredicateOp.GROUP_FILLED -> Tri.of(ctxOf(p.args.single()).groupFilled(pathOf(p.args.single())))
            PredicateOp.GROUP_NOT_FILLED -> Tri.of(!ctxOf(p.args.single()).groupFilled(pathOf(p.args.single())))
            PredicateOp.FIELD_VALUE_IN_LIST -> valueInList(p.args)
            PredicateOp.FIELD_VALUE_NOT_IN_LIST -> Kleene.not(valueInList(p.args))
            PredicateOp.DATE_RANGES_OVERLAP -> overlapAny(p.args).truth
            PredicateOp.ATLEAST_ONE_DATE_RANGE_OVERLAPS -> atLeastOneOverlap(p.args).truth
            PredicateOp.FIELD_VALUES_NOT_UNIQUE -> fieldValuesNotUnique(p.args)
            // An ordinary per-row 3VL leaf (IF149): the row's key is a duplicate (TRUE) or not (FALSE), read from
            // the call-scoped duplicate cache. The polarity (VALUE/OMISSION) rides [rnuOutcome] and is typed in
            // [predType]; here only truth matters. Degrade-loud if the leaf wasn't wired (see [rnuOutcomeAt]).
            PredicateOp.REPETITION_NOT_UNIQUE -> Tri.of(rnuOutcomeAt() != null)
            // Valid/Invalid(Date(...)): 3VL over the construction's status. A malformed part makes the whole
            // non-evaluable (UNKNOWN — both go quiet, the formal error fires); else Valid iff real-and-all-present,
            // Invalid its complement (unreal OR an empty part).
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
            // COMPUTE mode replaces the tally with the kernel's ordered SCAN for the FIELD-fill family (IF85):
            // each visited cell is a read (an invalid one poisons), and the per-operator stop ends the scan —
            // a later invalid cell is never read. Group-fill quantifiers keep the tally (group presence reads
            // the fill caches, not cell values).
            PredicateOp.ALL_FIELDS_FILLED, PredicateOp.NO_FIELD_FILLED, PredicateOp.AT_LEAST_ONE_FIELD_FILLED,
            PredicateOp.MORE_THAN_ONE_FIELD_FILLED, PredicateOp.NOT_ALL_FIELDS_FILLED,
            PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED, PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED,
            PredicateOp.ALL_GROUPS_FILLED, PredicateOp.NO_GROUP_FILLED, PredicateOp.AT_LEAST_ONE_GROUP_FILLED,
            PredicateOp.NOT_ALL_GROUPS_FILLED, PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED -> when {
                computeMode && !op.countsGroups -> computeFillQuantifier(op, p.args)
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
            val fs = if (a is Expr.Star && a.having == null && a.path in indexFields) indexStarFillScope(c, a.path)
                     else c.fillScope(path, isGroup = c.declaredKind(path) == null)
            t += fs.tInst; f += fs.fInst; n += fs.nInst; unknown = unknown || fs.hasUnknownInst
            allFilled = allFilled && fs.allFilledDeclared
            anyEmpty = anyEmpty || fs.anyEmptyDeclared
        }
        return FillScope(t, f, n, unknown, allFilled, anyEmpty)
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
     * value read). `NotExactlyOne` and `FieldsNotCollectivelyFilled` compose two scans, like the kernel.
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
     * in list order, a star's rows in row order, a `Having` filter evaluated per row inside the scan (its reads
     * can poison too). An INVALID visited cell throws ([PoisonedReadException]); [declaredTail] appends the
     * DECLARED-range emptiness after a star/group's instantiated cells (an un-instantiated rep reads empty —
     * no value read, so it cannot poison; kernel `alleEntitiesAngegeben`'s declared iteration).
     */
    private fun scanFilled(args: List<Expr>, declaredTail: Boolean): Sequence<Boolean> = sequence {
        for (a in args) when {
            a is Expr.Star && a.having == null -> {
                val c = ctxOf(a)
                for (state in c.starScanStates(a.path, a.bindDepth)) yield(filledOrPoison(a.path, state))
                if (declaredTail && c.fillScope(a.path, isGroup = false).anyEmptyDeclared) yield(false)
            }
            a is Expr.Star -> {
                // A filtered star: the filter runs per row inside the scan (its reads poison row-correctly); an
                // un-instantiated declared rep cannot pass a filter, so there is no declared tail.
                val group = a.path.substringBeforeLast('/')
                for (rc in ctxOf(a).rowContexts(group, a.bindDepth)) {
                    if (!havingInterp(rc).fires(a.having!!)) continue
                    yield(filledOrPoison(a.path, rc.cellState(a.path)))
                }
            }
            a is Expr.Field && ctxOf(a).declaredKind(a.path) == null -> {
                // A GROUP scope: scan its contained fields in DECLARATION order, each a lazy filled/empty read that
                // poisons on a malformed cell — so an operator's early-stop skips a later invalid cell, the kernel's
                // ordered getAllContainedFields scan (IF147). Was an eager whole-group tally that poisoned on ANY
                // unknown regardless of a preceding decisive cell (the IF147 over-poison, probe-confirmed).
                val c = ctxOf(a)
                for (state in c.containedFieldStates(a.path)) yield(filledOrPoison(a.path, state))
                if (declaredTail && c.fillScope(a.path, isGroup = true).anyEmptyDeclared) yield(false)
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

    private fun filledOrPoison(path: String, state: CellState): Boolean = when (state) {
        CellState.INVALID -> throw PoisonedReadException(path)
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
            ?: customFieldTypes[typeName]?.let { ct -> { v: String -> ct.check(v) == null } }
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
    private fun filledTri(c: EvalContext, path: String): Tri = when {
        c.presenceUnknown(path) -> Tri.UNKNOWN   // presence itself is 3VL (invalid parallel index) — before filled
        // COMPUTE mode: a presence check READS the cell — an invalid one poisons (IF85), row-correct via the
        // context's own cellState (a `Having` filter row judges ITS row's cell, not the computation's).
        computeMode && c.cellState(path) == CellState.INVALID -> throw PoisonedReadException(path)
        !c.filled(path) -> Tri.FALSE
        invalid(path) -> Tri.UNKNOWN
        else -> Tri.TRUE
    }

    /** A list quantifier's operand tally as `(t, f)` — `t` = TRUE (present + check-relevant), `f` = FALSE
     *  (check-relevant but empty); the rest are UNKNOWN (present but not check-relevant — a malformed value),
     *  counted in NEITHER, matching the kernel's `isWertAngegeben` (the shared [presenceTri], which also
     *  resolves a SEMANTIC-INDEX operand to its one matched cell — `SemanticIndexPresenceDiffTest`). GROUP
     *  operands have no UNKNOWN state (a group can't be malformed), so `f = n - t`. The kernel counts only TRUE
     *  for "filled" and only FALSE for "empty"; a malformed field is neither — so e.g. `AtLeastOneFieldFilled`
     *  does NOT fire on a lone malformed field. A `$`-marked operand tallies against the enclosing row (ctxOf). */
    private fun quantTally(op: PredicateOp, args: List<Expr>): Pair<Int, Int> {
        if (op.countsGroups) {
            val t = args.count { ctxOf(it).groupFilled(pathOf(it)) }
            return t to (args.size - t)
        }
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

    /** The quantifier's `(TRUE, FALSE, N)` tally — a STARRED operand expands to its filtered rows: a
     *  countsGroups predicate's star is a starred repeatable GROUP ([starGroupTally]); a field quantifier's star
     *  is a field-list (`AtLeastOneFieldFilled(Group* / Field Having …)`, [starQuantTally]); else a plain
     *  field/group operand list. */
    internal fun quantCounts(op: PredicateOp, args: List<Expr>): Triple<Int, Int, Int> {
        val single = args.singleOrNull()
        if (single is Expr.Star) return if (op.countsGroups) starGroupTally(single) else starQuantTally(single)
        val (t, f) = quantTally(op, args)
        return Triple(t, f, args.size)
    }

    /**
     * Tally a STARRED single repeatable-GROUP quantifier operand — `NoGroupFilled(G*)` / `AtLeastOneGroupFilled(G*)`.
     * Unlike a field-list star ([starQuantTally], whose path ends at a FIELD), the operand IS the group, and EVERY
     * instantiated row counts as a "filled" group (a created row is content — IF46, matching `NumberOfFilledGroups`),
     * so `t = n = instantiated (Having-matching) rows` and `f = 0` (probe-verified: the kernel does NOT fire
     * `NoGroupFilled(G*)` on one instantiated-but-empty row, and DOES fire `AtLeastOneGroupFilled(G*)`). The kernel
     * forbids `*` on a group for the AllGroupsFilled / NotAllGroupsFilled / collectively family
     * (`MVK_NO_WILDCARDS_G_ALLOWED`), so only the count-zero / count-≥1 predicates reach here on a valid model.
     * (The earlier code mis-routed this to [starQuantTally], which resolved the group's PARENT and counted zero —
     * an over-fire of `NoGroupFilled` and an under-fire of `AtLeastOneGroupFilled`; IF68.)
     */
    private fun starGroupTally(star: Expr.Star): Triple<Int, Int, Int> {
        val rows = ctxOf(star).rowContexts(star.path, star.bindDepth)
        val n = if (star.having == null) rows.size else rows.count { havingInterp(it).fires(star.having) }
        return Triple(n, 0, n)
    }

    /**
     * Tally a STARRED field-list quantifier operand — `AtLeastOneFieldFilled(Group* / Field Having …)`: iterate
     * the star's group rows, keep those the optional `Having` filter accepts, and count each kept row's cell as
     * TRUE (present + check-relevant) / FALSE (empty) / neither (malformed) — the same 3-state as [quantTally].
     * **Linear in the group's rows** (O(1) per row: the filter's `$` refs resolve against the fixed enclosing
     * row via [havingInterp], and `filled`/`rowContexts` are O(1)/O(rows)). Returns `(t, f, n = kept rows)`.
     */
    private fun starQuantTally(star: Expr.Star): Triple<Int, Int, Int> {
        val group = star.path.substringBeforeLast('/')
        // Fast path: an equality self-correlation Having (`[X] == [$X]` ± a CurrentRepetition self-exclusion) is
        // a HASH JOIN — O(1) per outer row against buckets the Document builds + caches once (O(N)). This is the
        // kernel's own linear strategy; the O(N²) scan below is the fallback for an arbitrary Having. A MULTI-star
        // operand (bindDepth above the leaf group's parent — IF94) hash-joins too (IF144): the buckets widen to the
        // first-star scope via [hashJoinTally]'s `bindDepth`. The one multi-star shape that STAYS naive is a
        // SELF-EXCLUSION (`CurrentRepetition(G) != CurrentRepetition($G)`): across the wider scope it drops EVERY
        // row sharing the outer's leaf index, not just the outer's own row, which the hash join's single-row
        // self-subtraction cannot express (the single-star scope makes that index unique, so single-star is safe).
        val join = star.having?.let { CorrelationJoin.parse(it, group) }
        if (join != null && (!isMultiStar(star) || !join.selfExcluded)) return hashJoinTally(star, group, join)
        return naiveStarQuantTally(star, group)
    }

    /** Whether [star] binds ABOVE its leaf group's parent — a multi-star operand (`A* / B* / F`, IF94). */
    private fun isMultiStar(star: Expr.Star): Boolean =
        star.bindDepth >= 0 && star.bindDepth < star.path.trim('/').split('/').size - 2

    /**
     * The O(N²) inner-scan `(t, f, n)` tally of a starred filter quantifier — the general fallback for an
     * arbitrary `Having`, and the **reference** the hash-join fast path must equal on a recognized correlation
     * `Having` (WI-4 property 6, [correlationTallyBothWays]). Iterates the group's rows, keeps those the filter
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

    // ---- WI-4 property seams (proposal §8; internal — consumed only by the property pack in commonTest) -------

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
     * **Property 6 (hash-join ≡ nested-loop).** For a starred filter quantifier [star] whose `Having` is a
     * recognized `$`-equality self-correlation, the `(t, f, n)` tally computed by the O(1) hash-join fast path
     * ([hashJoinTally]) paired with the O(N²) naive inner scan ([naiveStarQuantTally]) — the property asserts the
     * two are equal on every sampled input. Returns null when [star] carries no recognized correlation `Having`
     * (no fast path exists to compare against the scan).
     */
    internal fun correlationTallyBothWays(star: Expr.Star): Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>>? {
        val group = star.path.substringBeforeLast('/')
        val join = star.having?.let { CorrelationJoin.parse(it, group) } ?: return null
        if (isMultiStar(star) && join.selfExcluded) return null   // this shape uses the naive path only — no fast path to compare (IF144)
        return hashJoinTally(star, group, join) to naiveStarQuantTally(star, group)
    }

    /**
     * The `(t, f, n)` of a filtered quantifier whose `Having` is an equality self-correlation — computed by a
     * HASH JOIN (O(1) per outer row) instead of the O(N²) inner scan: look up the outer row's bucket by its
     * OUTER-field values, then remove the outer row's own contribution when a self-exclusion is present (it sits
     * in its own bucket iff its inner-field values equal its outer-field values). Returns exactly what the scan
     * would for well-formed data (the differential + fuzz lock it). Buckets are built + cached by the Document.
     */
    private fun hashJoinTally(star: Expr.Star, group: String, join: JoinSpec): Triple<Int, Int, Int> {
        val buckets = ctx.correlationBuckets(group, join.innerFields, star.path, star.bindDepth)
        val outerKey = join.outerFields.map { p -> if (invalid(p)) Val.Unknown else ctx.field(p) }
        if (outerKey.any { it is Val.Unknown }) return Triple(0, 0, 0)   // an Unknown outer value never matches
        val tfn = buckets[outerKey] ?: return Triple(0, 0, 0)
        var t = tfn[0]
        var f = tfn[1]
        var n = tfn[2]
        if (join.selfExcluded && ctx.evaluated) {
            val outerInnerKey = join.innerFields.map { p -> if (invalid(p)) Val.Unknown else ctx.field(p) }
            if (outerInnerKey == outerKey) {           // the outer row is in its own bucket → drop it
                n--
                when {
                    ctx.presenceUnknown(star.path) -> {}
                    !ctx.filled(star.path) -> f--
                    // a filled-but-INVALID own cell contributed to NEITHER bucket (IF105) — nothing to undo
                    ctx.field(star.path) is Val.Unknown -> {}
                    else -> t--
                }
            }
        }
        return Triple(t, f, n)
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
     * Polarity is **filter-derived** (not fill-derived): OMISSION iff a filled cell of a FILTER-bearing operand
     * (a rep-list arg carrying a `Having`) was processed at/before the fire — the `containsFilter` flag evolves
     * in operand order, matching the kernel exactly. Returns the (truth, polarity) [Res] so [evalPred] and
     * [predType] share ONE scan (KERNEL-FINDINGS §6, IF72).
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
     * each list cell; it is NOT a member of the set, so there is no self-pair. A not-filled/malformed scalar →
     * FALSE (no fire — NOT unknown-suppression). Polarity: OMISSION iff the MATCHED container (the list operand
     * whose cell overlapped) carries a `Having`, VALUE otherwise (KERNEL-FINDINGS §6, IF72).
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
    private fun overlapCells(arg: Expr): List<Val.Range> = when (arg) {
        is Expr.Star -> filteredStarCells(arg).filterIsInstance<Val.Range>()
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

    /** Closed-interval overlap of two ISO date ranges (lexicographic compare = chronological on ISO). An INVALID
     *  range (start > end) never overlaps anything (kernel `DatumBereich.ueberlappt` returns false for either
     *  side invalid), so both sides must be well-formed first. Overlap ⇔ `end1 ≥ start2 ∧ end2 ≥ start1` (closed —
     *  touching ranges overlap; KERNEL-FINDINGS §6). */
    private fun rangesOverlap(x: Val.Range, y: Val.Range): Boolean =
        x.start <= x.end && y.start <= y.end && x.end >= y.start && y.end >= x.start

    /** `value PatternMatched/PatternViolated "regex"` — anchored full-value match; empty → not evaluated. */
    internal fun evalPattern(p: Cond.Pattern): Tri {
        val v = evalExpr(p.value)
        if (v is Val.Unknown) return Tri.UNKNOWN
        val text = (v as? Val.Txt)?.v ?: error("pattern comparison needs a string value, got $v")
        val matched = Regex((p.pattern as Expr.StrLit).v).matches(text)
        return Tri.of(if (p.expectMatch) matched else !matched)
    }

    internal fun evalCmp(c: Cond.Cmp): Tri {
        // Overlap-predicate Having: a comparison touching an unbound `$` cannot be compiled (no enclosing
        // iteration) → the kernel treats the term as trivially TRUE (KERNEL-FINDINGS §6). This is why a
        // `$`-self-"exclusion" keeps every row, unlike a malformed real operand (which drops, evaluated below).
        if (outerUnbound && (hasUnboundOuter(c.left) || hasUnboundOuter(c.right))) return Tri.TRUE
        val l = evalCmpOperand(c.left, c.right)
        val r = evalCmpOperand(c.right, c.left)
        if (l is Val.Unknown || r is Val.Unknown) return Tri.UNKNOWN
        // The kernel's `vergleicheSTRING` returns false when EITHER operand is the empty string — for `==` AND
        // `!=` alike (an empty operand never matches and never differs). So an empty `RangeAsString` result, or a
        // literal `""`, suppresses the comparison (it does not fire) rather than comparing as a value. An empty
        // string *field* already arrives as Unknown (handled above); this covers the non-field empty strings.
        if ((l is Val.Txt && l.v.isEmpty()) || (r is Val.Txt && r.v.isEmpty())) return Tri.UNKNOWN
        // A DATETIME comparison under a non-UTC model zone compares zone-parsed INSTANTS (the kernel's
        // `vergleicheDATUM` on `VkDate` epoch millis), derived STRUCTURALLY so a chained `Add{Hours,…}` keeps
        // its exact instant — an ambiguous fall-back wall time re-parses as STANDARD, so the equal-looking
        // rendering of a daylight-side chain result is a DIFFERENT instant (KERNEL-FINDINGS §6). Under UTC the
        // canonical local ISO order IS the instant order, so the plain text compare below stays the fast path.
        if (timeZone != ModelTimeZone.UTC && l is Val.Txt && r is Val.Txt && isIsoDateTime(l.v) && isIsoDateTime(r.v)) {
            val li = dateTimeInstant(c.left) ?: return Tri.UNKNOWN
            val ri = dateTimeInstant(c.right) ?: return Tri.UNKNOWN
            return when (c.op) {
                CmpOp.EQ -> Tri.of(li == ri)
                CmpOp.NE -> Tri.of(li != ri)
                CmpOp.LT -> Tri.of(li < ri)
                CmpOp.LE -> Tri.of(li <= ri)
                CmpOp.GT -> Tri.of(li > ri)
                CmpOp.GE -> Tri.of(li >= ri)
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

    /** The 3VL presence of a field-valued predicate/quantifier operand — a plain field ref ([filledTri]) or a
     *  semantic index ([semanticIndexPresence]). Any other operand shape is not a presence operand. */
    internal fun presenceTri(e: Expr): Tri = when {
        outerUnknown(e) -> Tri.UNKNOWN   // an unbound `$` presence operand (overlap-predicate Having) → UNKNOWN
        e is Expr.SemanticIndex -> semanticIndexPresence(e)
        else -> filledTri(ctxOf(e), pathOf(e))
    }

    /**
     * `FieldValuesNotUnique(…)` — fires iff two of the operands' values are **equal**, i.e. the present values
     * are not all distinct (kernel codegen). Two operand shapes, both kernel-probed and identical in semantics:
     * a **plain field list** `(f1, f2, …)` (distinctness across named sibling fields) and a **starred** operand
     * `(Items* / Count)` (distinctness across the repetition's rows — the no-`--group` alternative to
     * `RepetitionNotUnique`). In both, **empties are skipped** (two empties are NOT a duplicate — only present
     * values compare); a filled-but-malformed operand makes the rule non-evaluable → suppressed (Unknown).
     * Equality is the typed [eq] (numbers numerically — `5` == `5.00` across scales — strings as strings). The
     * collected list is short, so an O(k²) pairwise scan is fine.
     */
    private fun fieldValuesNotUnique(args: List<Expr>): Tri {
        val values = mutableListOf<Val>()
        for (a in args) when (a) {
            is Expr.Star -> {
                // filled row values (empties dropped) — an optional `Having` filters the rows (kernel-probed: FVNU
                // ACCEPTS + EVALUATES a filter, the overlap-predicate keep-law; unlike SumOfProducts, which the
                // kernel authoring-rejects). A malformed kept cell → non-evaluable (suppressed).
                val rows = filteredStarCells(a)
                if (rows.any { it is Val.Unknown }) return Tri.UNKNOWN  // a malformed row → non-evaluable
                values.addAll(rows)
            }
            else -> {
                if (!ctx.filled(pathOf(a))) continue             // empty → skipped (no value to compare)
                val v = evalExpr(a)
                if (v is Val.Unknown) return Tri.UNKNOWN          // a filled-but-malformed operand → non-evaluable
                values.add(v)
            }
        }
        // O(n) duplicate detection via canonical keys. The starred operand collects ONE value per row, so the
        // old O(n²) pairwise equality scan went quadratic on a large repetition list (confirmed super-linear; the
        // kernel memoizes this via its RepetitionNotUniqueErrorCache). The key is [Aggregates.distinctKeyOrNull]
        // — the SAME keying `NumberOfDifferentValues` uses (numbers scale-19-insensitive, strings/bools by
        // themselves), so distinctness and duplicate-detection agree; a `Range`/other never compares equal → no
        // key → skipped (it cannot form a duplicate).
        val seen = HashSet<String>(values.size * 2)
        for (v in values) {
            val k = Aggregates.distinctKeyOrNull(v) ?: continue
            if (!seen.add(k)) return Tri.TRUE
        }
        return Tri.FALSE
    }

    /** The path of a plain field-ref operand. Any other operand shape in a field-ref position is a construct
     *  this evaluator does not model there — reported UNSUPPORTED (the per-rule tolerance catches it), never a
     *  ClassCastException that would sink the whole validation (the per-rule degrade net, IF61). */
    internal fun pathOf(e: Expr): String = (e as? Expr.Field)?.path
        ?: throw UnsupportedConstructException("unsupported operand ${e::class.simpleName} in a field-reference position")
}
