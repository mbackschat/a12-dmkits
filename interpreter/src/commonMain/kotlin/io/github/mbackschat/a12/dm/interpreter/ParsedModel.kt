package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.ToleranceBand
import io.github.mbackschat.a12.dm.interpreter.parse.RefResolver
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression

/** A computation's parsed form: each tiered alternative's (precondition?, operation), under an optional
 *  whole-computation common precondition — all parsed once. The kernel selects the first alternative whose
 *  precondition holds (null precondition = unconditional); a present common precondition gates them all. */
internal class ParsedComputation(val commonPrecondition: Cond?, val alternatives: List<ParsedAlternative>)

internal class ParsedAlternative(val precondition: Cond?, val operation: Expr, val tolerance: ToleranceBand?)

/**
 * The parse-once caches + the implicit computation-validation rules — a call-invariant collaborator of the
 * I2 eval-state seam (IF117). Allocated once per [DmInterpreter]; consumed by the rule path ([condAstOf]),
 * the `RepetitionNotUnique` recognizer (via the caller-supplied AST), and the [ComputationEngine]
 * ([parsedOf] + [implicitRules]).
 */
internal class ParsedModel(
    private val model: ModelDef,
    private val observer: () -> PreparationObserver? = { null },
) {

    /**
     * The parser's bare-ref resolver, model-aware: `base/name` is used as-is when it is already a declared
     * entity (the kernel's `getEntity`-first, declaring-group precedence); else, iff the model's
     * `fieldRefByShortNameAllowed` is on, it resolves to a UNIQUE model-wide field short name — the kernel's
     * `CheckIdNameRefImpl` fallback (KERNEL-FINDINGS §10; IF119). An ambiguous (>1) short name is a kernel
     * reject (no valid model reaches here with one); a 0-match stays `base/name` (the loud "no kind declared"
     * failure, never a silent wrong value). Shared across every rule/computation parse of this model.
     */
    private val refResolver = RefResolver { base, name ->
        val candidate = "$base/$name"
        when {
            candidate in model.entityPaths -> candidate
            model.fieldRefByShortNameAllowed -> model.fieldsByShortName[name]?.singleOrNull() ?: candidate
            else -> candidate
        }
    }

    /**
     * **Parse once, reuse across calls** — the prepare-once optimization (INTERPRETER-PERFORMANCE.md "the
     * interpreter's two goals"). Each rule's condition / computation's expression is parsed to an AST exactly
     * ONCE, on first use, then cached for every subsequent `validateFull`/`compute`/`testRule` call and every
     * row. This is what makes the interpreter **on-par with the kernel at all rule counts**: the kernel
     * code-gens once at model-load, and this is the direct analog (the per-call re-parse was the #31 cost that
     * made interp/kernel cross 1.0 past ~48 rules). It is **lazy** (the constructor only builds the wrappers, no
     * parsing) so **startup stays fast** and a single-rule [testRule] forces only the one rule it touches — the
     * two goals, met without trading one for the other. `lazy {}` is SYNCHRONIZED, so the engine is safe to
     * reuse across threads (the kernel's caches are concurrent too); each AST is parsed exactly once even under
     * a race. A candidate rule not in the model (an ad-hoc [evalCandidate]) falls back to a one-off parse.
     */
    private val ruleCondAst: Map<RuleDef, Lazy<Cond>> =
        model.rules.associateWith { r -> lazy {
            observe(PreparationWork.RULE_AST, r.path)
            parseCondition(r.conditionText, r.group, refResolver)
        } }

    private val compParsedAst: Map<ComputationDef, Lazy<ParsedComputation>> =
        model.computations.associateWith { c -> lazy {
            observe(PreparationWork.COMPUTATION_AST, c.targetPath)
            parseComputation(c)
        } }

    /** The implicit per-computation validation rules (IF32/IF65): one synthetic [RuleDef] per named computation,
     *  with its condition AST **built directly from the already-parsed alternatives** ([parsedOf]) rather than
     *  re-parsed from text — so the embedded operation/precondition ASTs are byte-identical to the ones the
     *  compute path uses, and no fragile string round-trip can fail to re-parse (IF65). The AST is cached (lazy,
     *  forced by [condAstOf]) exactly like a model rule's. */
    private val implicitComputationAst: Map<RuleDef, Lazy<Cond>> =
        model.computations.mapNotNull { c -> implicitComputationRuleDef(c)?.let { rule ->
            rule to lazy {
                observe(PreparationWork.IMPLICIT_RULE_AST, rule.path)
                implicitComputationCond(c)
            }
        } }.toMap()

    private fun observe(work: PreparationWork, key: String) {
        observer()?.performed(PreparationEvent(work, key))
    }

    private fun parseComputation(c: ComputationDef): ParsedComputation = ParsedComputation(
        c.commonPrecondition?.let { parseCondition(it, c.group, refResolver) },
        c.alternatives.map { ParsedAlternative(it.precondition?.let { p -> parseCondition(p, c.group, refResolver) }, parseExpression(it.operation, c.group, refResolver), it.tolerance) },
    )

    /** The parsed condition AST for [rule] — cached for a model rule or an implicit computation-validation rule;
     *  a one-off parse for an ad-hoc candidate. */
    fun condAstOf(rule: RuleDef): Cond =
        ruleCondAst[rule]?.value ?: implicitComputationAst[rule]?.value ?: parseCondition(rule.conditionText, rule.group, refResolver)

    /** The parsed form of [comp] (a model computation) — cached. */
    fun parsedOf(comp: ComputationDef): ParsedComputation = compParsedAst.getValue(comp).value

    /** The synthetic [RuleDef] for [comp]'s implicit validation (IF32/IF65): error code = the computation's NAME,
     *  rule element at `<group>/<name>` (a sibling of the computed field, like the kernel's rule-from-computation).
     *  The error entity is the computed field [ComputationDef.targetPath]; the synthetic error-entity relpath is the
     *  general relative path FROM the rule element TO that absolute target ([relativePath], matching how
     *  [resolveErrorPath] climbs `../` from [RuleDef.path]).
     *
     *  The computed field may NOT be a descendant of the computation's group — a computation can target a field in a
     *  sibling subtree, or in ANOTHER top-level root (a `header`/`content` multi-root model), its DM-JSON
     *  `computedFieldRelPath` walking `../` up past its own group. The earlier form `"../" + targetPath.removePrefix(group)`
     *  assumed the target sat under the group: for a cross-subtree target the `removePrefix` no-op'd and left an
     *  absolute path, so the relpath became a `..//abs/path` double-slash that [resolveErrorPath] mangled into a
     *  wrong, over-long pointer (IF66, graduated from IG41). [relativePath] resolves to the exact target for all three
     *  shapes (descendant, sibling-subtree, cross-root). Its condition AST is supplied by [implicitComputationCond]
     *  via [implicitComputationAst], never parsed from the placeholder [RuleDef.conditionText]. Null when the
     *  computation is unnamed or has no alternatives. */
    private fun implicitComputationRuleDef(comp: ComputationDef): RuleDef? {
        val name = comp.name ?: return null
        if (comp.alternatives.isEmpty()) return null
        // Anchored at the TARGET's parent group, NOT the declaring group — the kernel's rule-from-computation
        // fires at the error entity (the computed field), so a computation declared in an EMPTY container (a
        // `*_Rules` root targeting another subtree/root) must not be row-gated away by its declaring group's
        // lack of content (IF100 — the validation twin of IF89's "a computation iterates where it WRITES").
        // A cross-group REPEATABLE target never reaches the runtime (codegen-rejected,
        // MVK_ERROR_FIELD_NOT_IN_RULEGROUP), so the anchor move changes no per-row iteration.
        val group = parentGroup(comp.targetPath)
        val node = "$group/$name"
        return RuleDef(node, group, "FieldFilled(${comp.targetPath}) And <implicit validation of $name>",
            name, relativePath(node, comp.targetPath))
    }

    /**
     * The implicit computation-validation condition AST, built directly from [comp]'s already-parsed alternatives
     * ([parsedOf]) — mirroring the kernel's `ComputationAlternativesJoiner.join()` (md-model
     * `ValidationModelConverter.getComputationStatement`): a **field gate** `FieldFilled(F) And`, then the optional
     * `(commonPrecondition) And`, then the alternatives — a SINGLE alternative is `[(precondition) And] [F] != op`;
     * **>1** alternatives are the `Or` of each so-gated comparison. So the field is flagged when it is filled and,
     * for ANY tier whose precondition holds, its stored value disagrees with that tier's operation. This differs
     * deliberately from computation, whose ordered scan selects only the first holding alternative: overlapping
     * guards can compute the first operation while a later guarded mismatch still fires validation. Building the
     * AST from the parsed components (not a re-parsed string)
     * means the operation/precondition ASTs are identical to compute's and cannot fail to re-parse (the initial
     * string-synthesis attempt introduced parser crashes — IF65). An alternative that declares a
     * `toleranceRangeOp` compares with `DiffersWithToleranceRange{N}` (fires iff
     * `|R19(stored) − R19(op)| > N`) instead of strict `!=`, so a stored value within the band does not
     * over-fire (IF148/IF174, was IG96). */
    private fun implicitComputationCond(comp: ComputationDef): Cond {
        val parsed = parsedOf(comp)
        val fExpr = Expr.Field(comp.targetPath)
        fun alternative(a: ParsedAlternative): Cond {
            val cmp = a.tolerance?.let { Cond.Tolerance(fExpr, a.operation, dec(it.amount)) }
                ?: Cond.Cmp(CmpOp.NE, fExpr, a.operation)
            return a.precondition?.let { Cond.And(it, cmp) } ?: cmp
        }
        val body = parsed.alternatives.map(::alternative).reduce { l, r -> Cond.Or(l, r) }
        val guarded = parsed.commonPrecondition?.let { Cond.And(it, body) } ?: body
        return Cond.And(Cond.Pred("FieldFilled", listOf(fExpr)), guarded)
    }

    /** The implicit per-computation validation rules (IF32/IF65), evaluated through the same rule machinery. */
    val implicitRules: Collection<RuleDef> get() = implicitComputationAst.keys

    /** Logical initialized-cache entries for the structural cache-lifetime guard. */
    internal fun observeCacheEntries(observer: CacheObserver) {
        val initialized = ruleCondAst.values.count { it.isInitialized() } +
            compParsedAst.values.count { it.isInitialized() } +
            implicitComputationAst.values.count { it.isInitialized() }
        observer.cacheEntries(this, CacheOwnerKind.PARSED_MODEL, initialized)
    }
}
