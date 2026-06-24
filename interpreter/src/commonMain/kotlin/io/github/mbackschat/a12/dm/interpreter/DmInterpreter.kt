package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.MANDATORY_FIELD
import io.github.mbackschat.a12.dm.interpreter.eval.formalError
import io.github.mbackschat.a12.dm.interpreter.eval.formalErrorText
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.mandatoryFieldText
import io.github.mbackschat.a12.dm.interpreter.eval.UNIQUE_INDEX
import io.github.mbackschat.a12.dm.interpreter.eval.uniqueIndexText
import io.github.mbackschat.a12.dm.interpreter.eval.renderDateToFormat
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.FieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleResult
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import io.github.mbackschat.a12.dm.interpreter.model.Suppression
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import kotlin.jvm.JvmOverloads

/** Whether a field/group instance `(path, reps)` is relevant — full validation: always; partial: in the
 *  relevant set. Gates which rules run and which referenced fields are UNKNOWN. See [DmInterpreter.validatePart]. */
private typealias Relevance = (String, List<Int>) -> Boolean

/** The full-validation relevance: every instance is relevant. */
private val ALL_RELEVANT: Relevance = { _, _ -> true }

/**
 * The public entry point — evaluates a [ModelDef]'s rules over a [Document] instance, kernel-free, on
 * JVM and JS alike. The standalone-library façade (proposal goal 2) and the engine dmtool's native
 * image consumes for the runtime verbs (goal 1). Correctness = agrees with the real kernel (verified by
 * the `:adapter` differential tests).
 *
 * Full validation ([validateFull]/[validate]), partial validation ([validatePart]), single-rule eval
 * ([testRule]/[evalCandidate]), and computations ([compute]) — the kernel's `validateFull`/`validatePart`/
 * `compute` twins, kernel-free.
 */
class DmInterpreter @JvmOverloads constructor(
    private val model: ModelDef,
    private val clock: EvalClock? = null,
    /** The consumer-supplied predefined-type registry (`Valid`/`Invalid(field, "Name")`) — the kernel's
     *  `IPredefinedType` client SPI. Defaults to the built-in common set ([PredefinedTypeRegistry.DEFAULT]);
     *  a consumer may pass its own (built-ins + project types). An unregistered name → the rule is unsupported. */
    private val predefinedTypes: Map<String, PredefinedType> = PredefinedTypeRegistry.DEFAULT,
    /** Consumer-supplied custom conditions (the kernel's `ICustomCondition` SPI, cross-platform JVM+JS), keyed
     *  by name — `CustomCondition X`. Default empty: a model using an unregistered custom condition reports it
     *  unsupported (fail-fast). A JVM consumer adapts existing kernel `ICustomCondition` impls onto the SPI; a
     *  JS consumer implements the SPI directly. */
    private val customConditions: Map<String, CustomCondition> = emptyMap(),
) {

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
        model.rules.associateWith { r -> lazy { parseCondition(r.conditionText, r.group) } }
    private val compExprAst: Map<ComputationDef, Lazy<Expr>> =
        model.computations.associateWith { c -> lazy { parseExpression(c.expressionText, c.group) } }

    /** The parsed condition AST for [rule] — cached for a model rule; a one-off parse for an ad-hoc candidate. */
    private fun condAstOf(rule: RuleDef): Cond =
        ruleCondAst[rule]?.value ?: parseCondition(rule.conditionText, rule.group)

    /** The parsed expression AST for [comp] (a model computation) — cached. */
    private fun exprAstOf(comp: ComputationDef): Expr = compExprAst.getValue(comp).value

    /**
     * Run every rule over each row of its iteration scope and return one [Message] per firing — the
     * runtime twin of the kernel's `validateFull`. A rule fires when its error condition is TRUE on a row.
     * Convenience over [validate] returning just the messages (drops the unsupported-rule report).
     */
    fun validateFull(doc: Document, locale: String = "en_US"): List<Message> =
        validate(doc, locale).messages

    /**
     * The tolerant whole-document validation: each rule's firings as [Message]s, plus the rules that use
     * a construct the interpreter does not model (an unknown operator, or a deliberately external one
     * like `CustomCondition`) reported as [ValidationOutcome.unsupported] instead of crashing the run —
     * mirroring the kernel-adapter's `Opaque` read. Genuine bugs still throw.
     */
    fun validate(doc: Document, locale: String = "en_US"): ValidationOutcome =
        validateWith(doc, locale, ALL_RELEVANT)

    /**
     * Tolerant **partial** validation — the runtime twin of the kernel's `validatePart(DocumentV2,
     * relevantEntities, config)`. Only the rules whose **error field instance** is in [relevant] are
     * evaluated; of those, any reference to a field/group instance **not** in [relevant] resolves to
     * `Val.Unknown`, so the existing 3VL produces the kernel's exact suppression — `true And Unknown`
     * suppresses, `true Or Unknown` still fires (the "no possible value could prevent the error" clause is
     * just Kleene logic). The auto-generated checks (formal/mandatory/unique) are likewise gated by their
     * field's relevance. A relevant group makes all its descendants relevant; [RelevantEntity.ALL] wildcards
     * a repetition level (the encouraged form). With the whole document relevant this equals [validate].
     * KERNEL-FINDINGS "Partial validation".
     *
     * Boundaries (vs the kernel, documented): wildcard/aggregate references under a *subset* of relevant
     * repetitions follow the encouraged all-repetitions form exactly but are not yet pinned for arbitrary
     * subsets; a `CustomCondition` still sees `relevant = null` (full) in partial mode; global-field
     * auto-relevance is a model-structure concept the interpreter does not model.
     */
    fun validatePart(doc: Document, relevant: Set<RelevantEntity>, locale: String = "en_US"): ValidationOutcome =
        validateWith(doc, locale, relevanceOf(relevant))

    /**
     * The shared validation pipeline, parameterized by [isRelevant] (`(path, reps) → Boolean`). Full
     * validation passes [ALL_RELEVANT]; partial passes a relevance predicate. Formal errors first — the
     * kernel checks every field before evaluating rules: format errors on every filled-but-malformed field,
     * and `mandatoryField` on every required-but-empty field. Both make the field "unknown", so a rule
     * referencing it sees Val.Unknown (the 3VL propagates it). Every emitted message is gated by its error
     * field's relevance.
     */
    private fun validateWith(doc: Document, locale: String, isRelevant: Relevance): ValidationOutcome {
        val dup = duplicateIndexCells(doc)   // computed ONCE: feeds both the uniqueIndex messages and the suppression
        val messages = (formalErrors(doc, locale, isRelevant) + mandatoryErrors(doc, locale, isRelevant)
                + uniquenessMessages(dup, locale, isRelevant)).toMutableList()
        val unsupported = mutableListOf<UnsupportedRule>()
        for (rule in model.rules) {
            try {
                val rnu = repetitionNotUnique(rule)
                messages += if (rnu != null) repetitionNotUniqueMessages(rule, rnu, doc, locale, isRelevant)
                else messagesFor(rule, doc, locale, dup, isRelevant)
            } catch (e: UnsupportedConstructException) {
                unsupported += UnsupportedRule(rule.path, e.message ?: "unsupported construct")
            }
        }
        return ValidationOutcome(messages, unsupported)
    }

    /**
     * One formal-error [Message] per malformed filled field, document-wide — the runtime twin of the
     * kernel's `formalePruefung` checks. Channels match the kernel exactly (code, [FORMAL_RULE_PATH], field
     * pointer, ERROR, VALUE); the human text is the interpreter's own wording ([formalErrorText]), not the
     * kernel's German bundle (KERNEL-INTEGRATION-NOTES "Formal-error message text").
     */
    private fun formalErrors(doc: Document, locale: String, isRelevant: Relevance): List<Message> =
        doc.valuedFields().filter { isRelevant(it.path, it.reps) }.mapNotNull { vf ->
            val kind = model.kinds[vf.path] ?: return@mapNotNull null
            val scale = model.scaleOf(vf.path)
            formalError(kind, vf.raw, scale, model.dateLikeFormatOf(vf.path), model.datePrecisionOf(vf.path))?.let { code ->
                Message(code.id, FORMAL_RULE_PATH, formatPointer(vf.path, vf.reps), Severity.ERROR, MsgType.VALUE,
                    formalErrorText(code, locale, scale))
            }
        }

    /**
     * One `mandatoryField` [Message] per required-but-empty field instance — the runtime twin of the
     * kernel's autogenerated mandatory rules (KERNEL-FINDINGS §4). The polarity is always OMISSION (filling
     * the field clears it) and the pointer is the field itself. Two shapes, mirroring the kernel's
     * `relativeTo = lowestRepeatableGroupPath`:
     * - **unconditionally** required (mode [Requiredness.ABSOLUTE] with no repeatable ancestor): the field
     *   has exactly one canonical instance (all path parts non-repeatable → indices all 1); the kernel flags
     *   it even on an un-instantiated document, so it is checked directly via [Document.filledAt], not
     *   [Document.contextsFor].
     * - otherwise (absolute under a repeatable ancestor, or relative-to-parent): checked per instantiated row
     *   of the field's [emissionScope], gated by [requiredEmpty]'s `GroupFilled(relativeTo)`.
     */
    private fun mandatoryErrors(doc: Document, locale: String, isRelevant: Relevance): List<Message> {
        val out = mutableListOf<Message>()
        for (path in model.requiredFieldPaths) {
            if (model.requirednessOf(path) == Requiredness.ABSOLUTE && lowestRepeatableAncestor(path) == null) {
                val reps = List(pathParts(path).size) { 1 }
                if (!doc.filledAt(path, reps) && isRelevant(path, reps)) out += mandatoryMessage(path, reps, locale)
            } else {
                for (ctx in doc.contextsFor(emissionScope(path))) {
                    if (requiredEmpty(path, ctx) && isRelevant(path, ctx.repsOf(path))) {
                        out += mandatoryMessage(path, ctx.repsOf(path), locale)
                    }
                }
            }
        }
        return out
    }

    /**
     * One `uniqueIndex` [Message] per repeatable row whose index-field value DUPLICATES another row's within
     * the same parent scope — the runtime twin of the kernel's auto index-field uniqueness check
     * (KERNEL-FINDINGS). The kernel flags **every** row sharing a duplicated value (not all-but-first); an
     * empty index value is excluded (it is a requiredness concern → [mandatoryErrors]). Scoped per parent: a
     * nested group's uniqueness is within one parent row (Parts within a Line), not across all rows globally —
     * the scope key is the index field's reps minus its own + the group-row index ([List.dropLast] of 2).
     */
    /**
     * The `(indexFieldPath, reps)` cells whose value DUPLICATES another row's in the same parent scope — the
     * source for both the `uniqueIndex` messages ([uniquenessMessages]) AND the suppression: a duplicate index
     * is **"unknown"** for any rule referencing it ([formalErrorAt]), branch-scoped exactly like a formal error
     * (verified: a rule on the dup field is suppressed; a rule on a sibling field still fires). Computed once
     * per evaluation and threaded, not recomputed per operand.
     */
    private fun duplicateIndexCells(doc: Document): Set<Pair<String, List<Int>>> {
        val out = mutableSetOf<Pair<String, List<Int>>>()
        for (indexField in model.indexFields) {
            val rows = doc.contextsFor(parentGroup(indexField)).mapNotNull { ctx ->
                val reps = ctx.repsOf(indexField)
                ctx.displayValue(indexField)?.takeIf { it.isNotEmpty() }?.let { reps to it }
            }
            rows.groupBy { it.first.dropLast(2) }.forEach { (_, scope) ->
                val duplicated = scope.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys
                for ((reps, _) in scope.filter { it.second in duplicated }) out += indexField to reps
            }
        }
        return out
    }

    /** One `uniqueIndex` [Message] per duplicate-index cell (the kernel flags every participant). */
    private fun uniquenessMessages(dup: Set<Pair<String, List<Int>>>, locale: String, isRelevant: Relevance): List<Message> =
        dup.filter { (path, reps) -> isRelevant(path, reps) }.map { (path, reps) ->
            Message(UNIQUE_INDEX, uniqueRulePath(path), formatPointer(path, reps), Severity.ERROR, MsgType.VALUE,
                uniqueIndexText(locale))
        }

    /** A recognized `RepetitionNotUnique` rule: its key field paths + the optional `@From` reference-group ref. */
    private data class RnuRule(val keys: List<String>, val fromScope: String?)

    /**
     * If [rule]'s condition is a standalone `RepetitionNotUnique(key, … [@From ref])` over plain fields, its
     * keys + the optional `@From` ref; else null (a normal rule, handled per-row). The kernel forbids
     * groups/asterisks as keys, so a non-field key arg makes it unsupported (returns null).
     */
    private fun repetitionNotUnique(rule: RuleDef): RnuRule? {
        val ast = condAstOf(rule)
        if (ast !is Cond.Pred || ast.name != "RepetitionNotUnique") return null
        // The parser appends an `@From:<ref>` StrLit marker as the last arg when `@From` is present.
        val fromScope = (ast.args.lastOrNull() as? Expr.StrLit)?.v?.takeIf { it.startsWith("@From:") }?.removePrefix("@From:")
        val keyArgs = if (fromScope != null) ast.args.dropLast(1) else ast.args
        val keys = keyArgs.map { (it as? Expr.Field)?.path ?: return null }
        return keys.ifEmpty { null }?.let { RnuRule(it, fromScope) }
    }

    /**
     * The scope prefix length: the rep-prefix length of the **reference group's PARENT** row. The reference
     * group is the `@From` ref when given ([rnu].fromScope), else the **basic version** default — the first
     * repeatable group below the rule group on the key path (BA "basic version", Situation 2). So a key two
     * repeatable levels deep (`/Project/Milestones/Tasks/Effort`, rule at `/Project`) is by default checked
     * across ALL milestones (scope = the `/Project` row); `@From Milestones/Tasks` narrows it to per-milestone.
     */
    private fun scopePrefixLen(rule: RuleDef, rnu: RnuRule): Int {
        if (rnu.fromScope != null) return resolveGroupRef(rnu.fromScope, rule.group).trim('/').split('/').size - 1
        val parts = rnu.keys.first().trim('/').split('/')
        var d = rule.group.trim('/').split('/').size + 1     // first group strictly below the rule group
        while (d < parts.size && "/" + parts.take(d).joinToString("/") !in model.repeatable) d++
        return d - 1                                          // parent(referenceGroup) depth
    }

    /** Resolve a `@From` reference-group ref to an absolute group path — the `RuleGroup` keyword (the rule's
     *  group), an absolute `/…` path, or a path relative to the rule group (handling leading `..` segments). */
    private fun resolveGroupRef(ref: String, ruleGroup: String): String {
        if (ref == "RuleGroup") return ruleGroup
        if (ref.startsWith("/")) return ref
        val segs = ruleGroup.trim('/').split('/').toMutableList()
        for (s in ref.split('/')) when {
            s == ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1)
            s.isNotEmpty() -> segs.add(s)
        }
        return "/" + segs.joinToString("/")
    }

    /**
     * `RepetitionNotUnique(keys…)` — one [Message] per repetition row whose (composite) key DUPLICATES another
     * row's within the same parent-row scope (kernel-probed): empties match empties among participants, an
     * **all-empty** key row is skipped, and a duplicate whose key has an empty (fillable) component is
     * **OMISSION** (else VALUE). The rule sits at the parent and iterates the keys' repeatable group.
     */
    private fun repetitionNotUniqueMessages(rule: RuleDef, rnu: RnuRule, doc: Document, locale: String,
            isRelevant: Relevance = ALL_RELEVANT): List<Message> {
        val keys = rnu.keys
        val repGroup = parentGroup(keys.first())     // the keys' shared repeatable group (kernel: keys on one path)
        val errorPath = resolveErrorPath(rule)
        val scopeLen = scopePrefixLen(rule, rnu)
        data class Row(val ctx: EvalContext, val reps: List<Int>, val key: List<String?>)
        val rows = doc.contextsFor(repGroup).map { ctx ->
            Row(ctx, ctx.repsOf(errorPath), keys.map { ctx.displayValue(it)?.takeIf { v -> v.isNotEmpty() } })
        }
        val out = mutableListOf<Message>()
        rows.filter { row -> row.key.any { it != null } }      // participant: at least one key field is filled
            .groupBy { it.reps.take(scopeLen) }                // scope: per reference-group parent row (basic version)
            .forEach { (_, scope) ->
                val dups = scope.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
                for (row in scope.filter { it.key in dups }) {
                    if (!isRelevant(errorPath, row.reps)) continue   // partial: only if the error field is relevant
                    val type = if (row.key.any { it == null }) MsgType.OMISSION else MsgType.VALUE
                    val text = rule.messages[locale]?.let { renderMessage(it, rule.group, row.ctx) { p -> model.labelOf(p, locale) } } ?: ""
                    val referenced = keys.map { formatPointer(it, row.ctx.repsOf(it)) }
                    val fillToFix = if (type == MsgType.OMISSION) referenced else emptyList()
                    out += Message(rule.errorCode, rule.path, formatPointer(errorPath, row.reps),
                        rule.severity, type, text, referenced, fillToFix)
                }
            }
        return out
    }

    private fun mandatoryMessage(path: String, reps: List<Int>, locale: String): Message {
        val ptr = formatPointer(path, reps)
        return Message(MANDATORY_FIELD, mandatoryRulePath(path), ptr, Severity.ERROR, MsgType.OMISSION,
            mandatoryFieldText(locale), listOf(ptr), listOf(ptr))
    }

    /**
     * Whether the field at [path] is required-but-empty in [ctx] — the kernel's `mandatoryField` condition
     * AND the trigger that makes the field "unknown" for rule evaluation ([formalErrorAt]). Empty plus the
     * mode's gate: [Requiredness.ABSOLUTE] is unconditional with no repeatable ancestor, else gated by the
     * lowest repeatable ancestor being a live row; [Requiredness.RELATIVE_TO_PARENT] is gated by the
     * immediate parent being filled. Both gates ride [EvalContext.groupFilled] (repeatable = row exists,
     * non-repeatable = a descendant is filled).
     */
    private fun requiredEmpty(path: String, ctx: EvalContext): Boolean {
        if (ctx.filled(path)) return false
        return when (model.requirednessOf(path)) {
            Requiredness.NOT_REQUIRED -> false
            Requiredness.RELATIVE_TO_PARENT -> ctx.groupFilled(parentGroup(path))
            Requiredness.ABSOLUTE -> lowestRepeatableAncestor(path)?.let { ctx.groupFilled(it) } ?: true
        }
    }

    /**
     * Evaluate a SINGLE stored rule against the instance — the `rule test` shape. [Verdict.FIRED] (a
     * violation, with the rendered [RuleResult.message]) / [Verdict.PASSED] (evaluated, clean) /
     * [Verdict.SUPPRESSED] (never evaluated — a referenced field is formally invalid, named in
     * [RuleResult.suppressedBy]).
     */
    fun testRule(rulePath: String, doc: Document, locale: String = "en_US"): RuleResult {
        val rule = model.rules.firstOrNull { it.path == rulePath }
            ?: throw IllegalArgumentException("no such rule: $rulePath")
        return evalRule(rule, doc, locale)
    }

    /** Evaluate an injected, NON-stored candidate rule (the `model eval --condition/--field` shape). */
    fun evalCandidate(rule: RuleDef, doc: Document, locale: String = "en_US"): RuleResult =
        evalRule(rule, doc, locale)

    /**
     * Run every computation over each row of its iteration scope and return each computed-field outcome
     * — the runtime twin of the kernel's `compute()`. VALUE (the expression resolved, empty-as-0 applies)
     * / CLEARED (the expression computed nothing). ERRORED (an operand/computation error) awaits the
     * formal-error increment.
     */
    fun compute(doc: Document, locale: String = "en_US"): List<Computed> {
        val out = mutableListOf<Computed>()
        // **Cascade** — a computation may reference ANOTHER computation's target field; the kernel computes the
        // dependency first and the dependent reads its computed value (the CalculationCache). We mirror that
        // **side-effect-free**: compute in dependency order, accumulating each result into an [overlay] (the
        // computed values), and evaluate a dependent through an [OverlayContext] that returns those overlaid
        // values for computed-field reads. Without this the dependent read the stored (empty) operand → a wrong
        // value (verified vs the kernel). No doc mutation, so a reused doc never accumulates placements; for a
        // model with no inter-computation dependency the overlay is never consulted (zero behavior change).
        val overlay = HashMap<Pair<String, List<Int>>, Val>()
        for (comp in computationsInDependencyOrder()) {
            val expr = exprAstOf(comp)
            val scope = scopeFromRefs(exprRefs(expr) + comp.targetPath, comp.group)
            for (ctx in doc.contextsFor(scope)) {
                val ectx = if (overlay.isEmpty()) ctx else OverlayContext(ctx, overlay)
                val v = Interpreter(ectx, clock, model.baseYear, model.indexFields, predefinedTypes).evalExpression(expr)
                out += toComputed(comp, v)
                if (v !is Val.Unknown) overlay[comp.targetPath to ctx.repsOf(comp.targetPath)] = v
            }
        }
        return out
    }

    /** One computed-field outcome for [comp] from its evaluated [v]: VALUE (resolved, empty-as-0 applies) / CLEARED. */
    private fun toComputed(comp: ComputationDef, v: Val): Computed = when (v) {
        is Val.Num -> Computed(comp.targetPath, ComputeOutcome.VALUE, v.v.display())
        is Val.Txt -> Computed(comp.targetPath, ComputeOutcome.VALUE, v.v)
        is Val.Bool -> Computed(comp.targetPath, ComputeOutcome.VALUE, v.v.toString())
        is Val.Unknown -> Computed(comp.targetPath, ComputeOutcome.CLEARED)
        // A computed date-RANGE (`DateRange(start, end)`) — rendered to the TARGET field's (format, separator),
        // so `2024-06-01`/`2024-06-30` → `01.06.2024-30.06.2024` for a `dd.MM.yyyy`-`-` CoverageWindow. Falls
        // back to ISO `start/end` if the target declares no range config.
        is Val.Range -> Computed(comp.targetPath, ComputeOutcome.VALUE, renderRange(v, comp.targetPath))
    }

    /**
     * The model's computations in **dependency order** — a computation whose expression references another
     * computation's target field comes AFTER it (the cascade). A DFS topological sort; a cyclic dependency
     * (which the kernel rejects at codegen) throws. References are taken over ALL field/star paths so a
     * dependency through an aggregate over a computed field is ordered too.
     */
    private fun computationsInDependencyOrder(): List<ComputationDef> {
        val byTarget = model.computations.associateBy { it.targetPath }
        val ordered = mutableListOf<ComputationDef>()
        val done = HashSet<String>()
        val onStack = HashSet<String>()
        fun visit(c: ComputationDef) {
            if (c.targetPath in done) return
            if (!onStack.add(c.targetPath)) {
                throw UnsupportedConstructException("cyclic computation dependency involving ${c.targetPath}")
            }
            for (ref in referencedFieldPaths(exprAstOf(c))) {
                byTarget[ref]?.let { dep -> if (dep.targetPath != c.targetPath) visit(dep) }
            }
            onStack.remove(c.targetPath)
            if (done.add(c.targetPath)) ordered.add(c)
        }
        model.computations.forEach { visit(it) }
        return ordered
    }

    /**
     * Decorates an [EvalContext] so reads of a field that a PRIOR computation produced return its computed
     * value (the cascade overlay) — keyed by `(path, reps)`, no document mutation. Only the value/fill reads
     * are overlaid (a direct `[A]` / `FieldFilled(A)` reference to a computed field A); star aggregation over a
     * computed field is delegated to the real context (an exotic case, unchanged). All other methods delegate.
     */
    private class OverlayContext(
        private val d: EvalContext,
        private val overlay: Map<Pair<String, List<Int>>, Val>,
    ) : EvalContext by d {
        private fun overlaid(path: String): Val? = overlay[path to d.repsOf(path)]
        override fun field(path: String): Val = overlaid(path) ?: d.field(path)
        override fun filled(path: String): Boolean = overlaid(path)?.let { it !is Val.Unknown } ?: d.filled(path)
        override fun displayValue(path: String): String? = when (val v = overlaid(path)) {
            is Val.Num -> v.v.display()
            is Val.Txt -> v.v
            is Val.Bool -> v.v.toString()
            else -> d.displayValue(path)
        }
    }

    /** Every field/star path an expression reads — for cascade dependency ordering (incl. starred aggregates). */
    private fun referencedFieldPaths(expr: Expr): List<String> = when (expr) {
        is Expr.Field -> listOf(expr.path)
        is Expr.Star -> listOf(expr.path)
        is Expr.Func -> expr.args.flatMap { referencedFieldPaths(it) }
        is Expr.Arith -> referencedFieldPaths(expr.left) + referencedFieldPaths(expr.right)
        is Expr.EnumCategory -> listOf(expr.path)
        is Expr.SemanticIndex -> listOfNotNull(expr.path, expr.indexFieldPath)
        else -> emptyList()
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

    private fun evalRule(rule: RuleDef, doc: Document, locale: String): RuleResult {
        val rows = rowOutcomes(rule, doc, locale, duplicateIndexCells(doc))
        rows.firstNotNullOfOrNull { it as? RowOutcome.Fired }?.let {
            return RuleResult(rule.path, Verdict.FIRED, it.message.text, it.message.type)
        }
        // No firing: a referenced field being formally invalid means the rule was NOT evaluated (the
        // kernel skips it) → SUPPRESSED, not PASSED. PASSED only when every row was cleanly evaluated.
        val suppressed = rows.filterIsInstance<RowOutcome.Suppressed>().map { it.suppression }
        return if (suppressed.isNotEmpty()) RuleResult(rule.path, Verdict.SUPPRESSED, suppressedBy = suppressed)
        else RuleResult(rule.path, Verdict.PASSED)
    }

    /** Every firing of one rule across its iteration-scope rows, as [Message]s (suppressed rows produce none). */
    private fun messagesFor(rule: RuleDef, doc: Document, locale: String, dup: Set<Pair<String, List<Int>>>,
            isRelevant: Relevance = ALL_RELEVANT): List<Message> =
        rowOutcomes(rule, doc, locale, dup, isRelevant).filterIsInstance<RowOutcome.Fired>().map { it.message }

    /** The per-row outcome of one rule: fired (with its message), passed, or suppressed (which field/code). */
    private sealed interface RowOutcome {
        data class Fired(val message: Message) : RowOutcome
        data object Passed : RowOutcome
        data class Suppressed(val suppression: Suppression) : RowOutcome
    }

    private fun rowOutcomes(rule: RuleDef, doc: Document, locale: String, dup: Set<Pair<String, List<Int>>>,
            isRelevant: Relevance = ALL_RELEVANT): List<RowOutcome> {
        val ast = condAstOf(rule)
        val refs = nonStarredRefs(ast).distinct()
        val errorPath = resolveErrorPath(rule)
        return iterationContexts(rule, ast, doc).map { ctx ->
            // Partial validation: the kernel runs a rule only if its error-field instance is relevant — else
            // the rule is NOT executed for this row (no message, not a suppression). Full validation: always.
            if (!isRelevant(errorPath, ctx.repsOf(errorPath))) return@map RowOutcome.Passed
            // A formally-invalid referenced field resolves to Val.Unknown (NOT a whole-rule skip): the 3VL
            // propagates it, so a bare comparison goes UNKNOWN but `… Or <true>` still FIRES — matching the
            // kernel's operand-level "not check-relevant" behavior (verified). A duplicate-index field is
            // likewise unknown (branch-scoped, same as a formal error) via [formalErrorAt]. In partial mode a
            // referenced field instance that is NOT relevant is also unknown — the same 3VL yields the kernel's
            // suppression (`And` suppresses, `Or` with a relevant-true operand still fires). The custom-condition
            // registry + the row's error-field pointer ride along for a `CustomCondition X` leaf.
            val interp = Interpreter(ctx, clock, model.baseYear, model.indexFields, predefinedTypes,
                customConditions, FieldPointer(errorPath, ctx.repsOf(errorPath))) { path ->
                    formalErrorAt(path, ctx, dup) != null || !isRelevant(path, ctx.repsOf(path))
                }
            // ONE typed walk per row (the fired MsgType, or null if it doesn't fire) — replaces the old
            // fires()+firedType() pair, which walked the AST TWICE on every firing row. firedType != null ⟺
            // the rule fires (truth == TRUE), and the value IS the polarity, so a firing row now costs one walk.
            val firedType = interp.firedType(ast)
            if (firedType != null) {
                val type = firedType
                val text = rule.messages[locale]?.let { renderMessage(it, rule.group, ctx) { p -> model.labelOf(p, locale) } } ?: ""
                val referenced = refs.map { formatPointer(it, ctx.repsOf(it)) }
                // refOmissionErrorResponsible: for an OMISSION error the kernel attaches ALL the operands the
                // omission depends on (verified — both the filled and the empty operand of an And), so filling/
                // changing any could resolve it. Empty for a VALUE error (no fill fixes a value).
                val fillToFix = if (type == MsgType.OMISSION) referenced else emptyList()
                RowOutcome.Fired(Message(rule.errorCode, rule.path, formatPointer(errorPath, ctx.repsOf(errorPath)),
                    rule.severity, type, text, referenced, fillToFix))
            } else {
                // Not fired: a formally-invalid operand means the rule was NOT cleanly evaluated → SUPPRESSED
                // (the `rule test` third verdict), naming the offending field/code; else a clean PASSED.
                firstSuppressor(refs, ctx, dup)?.let { RowOutcome.Suppressed(it) } ?: RowOutcome.Passed
            }
        }
    }

    /** The first referenced field that is "unknown" in [ctx] (malformed value, required-empty, or duplicate index), else null. */
    private fun firstSuppressor(refs: List<String>, ctx: EvalContext, dup: Set<Pair<String, List<Int>>>): Suppression? =
        refs.firstNotNullOfOrNull { path -> formalErrorAt(path, ctx, dup)?.let { Suppression(path, it) } }

    /**
     * The "unknown-operand" code of the field instance at [path] in [ctx], or null when the field is usable.
     * The single source for "is this operand unknown?" (fed to the [Interpreter] → Val.Unknown, and to
     * [firstSuppressor] → the suppressor name), covering all three kernel causes: a present value's **format**
     * ([formalError]); an empty **required** value ([requiredEmpty] → [MANDATORY_FIELD]); and a **duplicate
     * index** value ([UNIQUE_INDEX], the [dup] cells) — a duplicate-index field is unknown for any rule
     * referencing it, branch-scoped exactly like a formal error (verified, KERNEL-FINDINGS §4 supplement).
     */
    private fun formalErrorAt(path: String, ctx: EvalContext, dup: Set<Pair<String, List<Int>>>): String? {
        val raw = ctx.displayValue(path)
        if (raw != null) {
            val kind = model.kinds[path] ?: return null
            formalError(kind, raw, model.scaleOf(path), model.dateLikeFormatOf(path), model.datePrecisionOf(path))?.id?.let { return it }
            return if (path to ctx.repsOf(path) in dup) UNIQUE_INDEX else null
        }
        return if (requiredEmpty(path, ctx)) MANDATORY_FIELD else null
    }

    /**
     * The contexts a rule is evaluated over. The normal case: one per row of its iteration **scope** — the
     * **deepest repeatable group** that is an ancestor of any *non-aggregated* field reference (a starred ref
     * is an aggregate and collapses the repetition, so it does NOT force iteration); with no repeatable ref, the
     * scope is the rule's declared [RuleDef.group]. This is the kernel law (KERNEL-FINDINGS §9 supplement,
     * `IterationLawsTest`): the iteration scope is the set of *referenced* repeatable fields, NOT the rule's node
     * placement — a rule at `/Order` reaching into `/Order/Items` iterates the Items rows.
     *
     * **Parallel iteration** — when the condition references TWO (or more) NON-nested repeatable groups per-row
     * (siblings, neither an ancestor of the other), the kernel iterates them JOINTLY as an outer join keyed by a
     * shared index field (KERNEL-SEMANTICS §9). [parallelIterationContexts] builds that join; an unsupported
     * parallel shape (non-sibling depth, missing / mismatched index field) fails loud (reported unsupported),
     * never silently wrong.
     */
    private fun iterationContexts(rule: RuleDef, ast: Cond, doc: Document): List<EvalContext> {
        val refs = nonStarredRefs(ast)
        val scope = scopeFromRefs(refs, rule.group)
        val parallelGroups = refs.flatMap { ancestorGroups(it).filter { g -> g in model.repeatable } }
            .distinct().filter { it != scope && !scope.startsWith("$it/") }
        return if (parallelGroups.isEmpty()) doc.contextsFor(scope)
        else parallelIterationContexts(scope, parallelGroups, doc)
    }

    /**
     * Build the parallel-iteration outer-join contexts: the joined groups (the [scope] group + the
     * [parallelGroups]) iterated jointly over the union of their shared index field's values (delegated to
     * [Document.parallelContexts]). Each joined group must carry the **same-named** index field. Non-repeatable
     * intermediates between the common ancestor and a joined group are transparent — sibling, wrapped, and
     * asymmetric-depth groups all qualify (the kernel renders the wrappers `[1]`). A *repeatable* non-indexed
     * intermediate above ONE joined group is a **frame**: the kernel iterates it normally and joins inside each
     * frame row (the `/Plan/X/A ⋈ B` case), supported via the `frameGroup` argument. The one **unsupported
     * shape** is a repeatable frame above MORE THAN ONE joined group (an exotic multi-frame cross-product),
     * which throws [UnsupportedConstructException] (→ rule reported unsupported, loud-not-wrong). An **invalid**
     * index (duplicate/empty) is NOT declined — modeled exactly: the compromised group's unmatched references
     * read 3VL UNKNOWN (KERNEL-FINDINGS §9), and the auto checks still emit `uniqueIndex`/`mandatoryField`.
     */
    private fun parallelIterationContexts(scope: String, parallelGroups: List<String>, doc: Document): List<EvalContext> {
        val joined = (listOf(scope) + parallelGroups).distinct()
        val commonParent = commonAncestor(joined)
        val parentDepth = pathParts(commonParent).size
        val indexNames = joined.map { g ->
            val indexField = model.indexFields.firstOrNull { parentGroup(it) == g }
                ?: throw UnsupportedConstructException("parallel iteration group $g has no index field")
            pathParts(indexField).last()
        }.distinct()
        if (indexNames.size != 1) {
            throw UnsupportedConstructException(
                "parallel-iterated groups must share one index field name, found $indexNames")
        }
        // A "framed" joined group sits under a repeatable non-indexed ancestor (strictly between the common
        // ancestor and it) — the kernel iterates that frame normally and joins inside each frame row (the
        // `/Plan/X/A ⋈ B` case). At most ONE joined group may be framed; a frame above two joined groups is an
        // exotic cross-product, not modeled → loud decline.
        fun frameAncestors(g: String) = ancestorGroups(g).filter { pathParts(it).size > parentDepth && it in model.repeatable }
        val framed = joined.filter { frameAncestors(it).isNotEmpty() }
        if (framed.size > 1) {
            throw UnsupportedConstructException(
                "parallel iteration with repeatable frames above multiple joined groups ($framed) is not modeled")
        }
        val frameGroup = framed.firstOrNull()
        val deepestFrame = frameGroup?.let { g -> frameAncestors(g).maxByOrNull { pathParts(it).size } }
        return doc.parallelContexts(joined, indexNames.single(), commonParent, frameGroup, deepestFrame)
    }

    /** The deepest common ancestor group path of [groups] (their longest shared path prefix). */
    private fun commonAncestor(groups: List<String>): String {
        val partsList = groups.map { pathParts(it) }
        var n = 0
        while (n < partsList.minOf { it.size } && partsList.all { it[n] == partsList[0][n] }) n++
        return "/" + partsList[0].take(n).joinToString("/")
    }

    /** The deepest repeatable group ancestor of any [refs] path, else [defaultGroup]. */
    private fun scopeFromRefs(refs: List<String>, defaultGroup: String): String =
        refs.flatMap { ref -> ancestorGroups(ref).filter { it in model.repeatable } }
            .maxByOrNull { it.count { c -> c == '/' } }
            ?: defaultGroup

    /** Every non-starred field-reference path in the condition (Star refs are aggregates, excluded). */
    private fun nonStarredRefs(cond: Cond): List<String> = when (cond) {
        is Cond.Cmp -> exprRefs(cond.left) + exprRefs(cond.right)
        is Cond.Tolerance -> exprRefs(cond.left) + exprRefs(cond.right)
        is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap { exprRefs(it) }
        is Cond.And -> nonStarredRefs(cond.left) + nonStarredRefs(cond.right)
        is Cond.Or -> nonStarredRefs(cond.left) + nonStarredRefs(cond.right)
        is Cond.Not -> nonStarredRefs(cond.c)
        is Cond.Pred -> cond.args.flatMap { exprRefs(it) }
        is Cond.Pattern -> exprRefs(cond.value) + exprRefs(cond.pattern)
        is Cond.Custom -> emptyList()   // the name references no field in the text — the rule is guarded by a field ref
    }

    private fun exprRefs(expr: Expr): List<String> = when (expr) {
        is Expr.Field -> listOf(expr.path)
        is Expr.Func -> expr.args.flatMap { exprRefs(it) }   // Star args excluded → drop through
        is Expr.Arith -> exprRefs(expr.left) + exprRefs(expr.right)
        is Expr.EnumCategory -> listOf(expr.path)            // `[field -> cat]` references the enum field
        // `[F For keyField]`: the KEY field is a per-row ref (drives iteration + is a referenced field); the
        // target `F` is an absolute semantic lookup (a specific repetition), not a per-row ref. A literal key
        // (`[F For "v"]`) references no field.
        is Expr.SemanticIndex -> expr.indexFieldPath?.let { listOf(it) } ?: emptyList()
        else -> emptyList()
    }

    private fun ancestorGroups(fieldPath: String): List<String> {
        val parts = fieldPath.trim('/').split('/')
        return (1 until parts.size).map { "/" + parts.take(it).joinToString("/") }
    }

    private fun pathParts(path: String): List<String> = path.trim('/').split('/')

    /** The field's immediate parent group, e.g. `/Order/Items/Count` → `/Order/Items`. */
    private fun parentGroup(path: String): String = "/" + pathParts(path).dropLast(1).joinToString("/")

    /** The field's topmost group (its root), e.g. `/Order/Items/Count` → `/Order`. */
    private fun rootGroupOf(path: String): String = "/" + pathParts(path).first()

    /** The deepest repeatable ancestor group of [path] (the kernel's `lowestRepeatableGroupPath`), or null. */
    private fun lowestRepeatableAncestor(path: String): String? =
        ancestorGroups(path).filter { it in model.repeatable }.maxByOrNull { it.count { c -> c == '/' } }

    /** The iteration scope a `mandatoryField` is checked over: the deepest repeatable ancestor (per-row), else
     *  the field's always-instantiated root group (a single context). */
    private fun emissionScope(path: String): String = lowestRepeatableAncestor(path) ?: rootGroupOf(path)

    /** The kernel's autogenerated mandatory-rule path for [path] — `<parent>/vk_vk<field>_req`. */
    private fun mandatoryRulePath(path: String): String = "${parentGroup(path)}/vk_vk${pathParts(path).last()}_req"

    /** The autogenerated uniqueness-rule path for an index [path] — `<parent>/vk_vk<field>_unique`. */
    private fun uniqueRulePath(path: String): String = "${parentGroup(path)}/vk_vk${pathParts(path).last()}_unique"

    /**
     * Resolve the rule's error-entity relative path to an absolute path. The relpath is relative to the
     * rule **node** (`rule.path`), so `../DeliveryDate` on `/Order/DeliveryNotBeforeOrder` pops the node
     * name → `/Order`, then appends → `/Order/DeliveryDate` (a sibling field in the rule's own group).
     */
    private fun resolveErrorPath(rule: RuleDef): String {
        var base = rule.path
        var rel = rule.errorEntityRelPath
        while (rel.startsWith("../")) {
            base = base.substringBeforeLast('/', "")
            rel = rel.removePrefix("../")
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    /**
     * Build the [Relevance] predicate from a relevant-entity set: an instance `(path, reps)` is relevant iff
     * some [RelevantEntity] **is** it or is an **ancestor group** of it, with the shared-prefix repetition
     * indices matching ([RelevantEntity.ALL] wildcards a level). A relevant group thus makes every descendant
     * relevant (the kernel rule). An empty set → nothing relevant (no rule runs).
     */
    private fun relevanceOf(relevant: Set<RelevantEntity>): Relevance =
        { path, reps -> relevant.any { matches(it, path, reps) } }

    /** Whether relevant entity [r] covers the instance at [path]/[reps] — [r.path] a prefix of [path] (== or a
     *  group ancestor) with each of [r]'s indices either [RelevantEntity.ALL] or equal to the instance's. */
    private fun matches(r: RelevantEntity, path: String, reps: List<Int>): Boolean {
        val rParts = pathParts(r.path)
        val pParts = pathParts(path)
        if (rParts.size > pParts.size || r.indices.size != rParts.size) return false
        if (rParts != pParts.take(rParts.size)) return false
        return r.indices.indices.all { i -> r.indices[i] == RelevantEntity.ALL || r.indices[i] == reps[i] }
    }
}
