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
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
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

/**
 * The public entry point — evaluates a [ModelDef]'s rules over a [Document] instance, kernel-free, on
 * JVM and JS alike. The standalone-library façade (proposal goal 2) and the engine dmtool's native
 * image consumes for the runtime verbs (goal 1). Correctness = agrees with the real kernel (verified by
 * the `:adapter` differential tests).
 *
 * Today: full validation ([validateFull]) and single-rule eval ([testRule]/[evalCandidate]). Partial
 * validation, computations, and formal-error suppression are the next increments (INTERPRETER-BUILDOUT.md).
 */
class DmInterpreter @JvmOverloads constructor(private val model: ModelDef, private val clock: EvalClock? = null) {

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
    fun validate(doc: Document, locale: String = "en_US"): ValidationOutcome {
        // Formal errors first — the kernel checks every field before evaluating rules: format errors on every
        // filled-but-malformed field, and `mandatoryField` on every required-but-empty field. Both make the
        // field "unknown", so a rule referencing it sees Val.Unknown (the 3VL propagates it; see below).
        val messages = (formalErrors(doc, locale) + mandatoryErrors(doc, locale)).toMutableList()
        val unsupported = mutableListOf<UnsupportedRule>()
        for (rule in model.rules) {
            try {
                messages += messagesFor(rule, doc, locale)
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
    private fun formalErrors(doc: Document, locale: String): List<Message> =
        doc.valuedFields().mapNotNull { vf ->
            val kind = model.kinds[vf.path] ?: return@mapNotNull null
            val scale = model.scaleOf(vf.path)
            formalError(kind, vf.raw, scale)?.let { code ->
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
    private fun mandatoryErrors(doc: Document, locale: String): List<Message> {
        val out = mutableListOf<Message>()
        for (path in model.requiredFieldPaths) {
            if (model.requirednessOf(path) == Requiredness.ABSOLUTE && lowestRepeatableAncestor(path) == null) {
                val reps = List(pathParts(path).size) { 1 }
                if (!doc.filledAt(path, reps)) out += mandatoryMessage(path, reps, locale)
            } else {
                for (ctx in doc.contextsFor(emissionScope(path))) {
                    if (requiredEmpty(path, ctx)) out += mandatoryMessage(path, ctx.repsOf(path), locale)
                }
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
        for (comp in model.computations) {
            val expr = parseExpression(comp.expressionText, comp.group)
            val scope = scopeFromRefs(exprRefs(expr) + comp.targetPath, comp.group)
            for (ctx in doc.contextsFor(scope)) {
                out += when (val v = Interpreter(ctx, clock).evalExpression(expr)) {
                    is Val.Num -> Computed(comp.targetPath, ComputeOutcome.VALUE, v.v.display())
                    is Val.Txt -> Computed(comp.targetPath, ComputeOutcome.VALUE, v.v)
                    is Val.Bool -> Computed(comp.targetPath, ComputeOutcome.VALUE, v.v.toString())
                    is Val.Unknown -> Computed(comp.targetPath, ComputeOutcome.CLEARED)
                }
            }
        }
        return out
    }

    private fun evalRule(rule: RuleDef, doc: Document, locale: String): RuleResult {
        val rows = rowOutcomes(rule, doc, locale)
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
    private fun messagesFor(rule: RuleDef, doc: Document, locale: String): List<Message> =
        rowOutcomes(rule, doc, locale).filterIsInstance<RowOutcome.Fired>().map { it.message }

    /** The per-row outcome of one rule: fired (with its message), passed, or suppressed (which field/code). */
    private sealed interface RowOutcome {
        data class Fired(val message: Message) : RowOutcome
        data object Passed : RowOutcome
        data class Suppressed(val suppression: Suppression) : RowOutcome
    }

    private fun rowOutcomes(rule: RuleDef, doc: Document, locale: String): List<RowOutcome> {
        val ast = parseCondition(rule.conditionText, rule.group)
        val refs = nonStarredRefs(ast).distinct()
        return doc.contextsFor(iterationScope(rule, ast)).map { ctx ->
            // A formally-invalid referenced field resolves to Val.Unknown (NOT a whole-rule skip): the 3VL
            // propagates it, so a bare comparison goes UNKNOWN but `… Or <true>` still FIRES — matching the
            // kernel's operand-level "not check-relevant" behavior (verified).
            val interp = Interpreter(ctx, clock) { path -> formalErrorAt(path, ctx) != null }
            if (interp.fires(ast)) {
                val type = interp.firedType(ast) ?: MsgType.VALUE
                val text = rule.messages[locale]?.let { renderMessage(it, rule.group, ctx) { p -> model.labelOf(p, locale) } } ?: ""
                val errorPath = resolveErrorPath(rule)
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
                firstSuppressor(refs, ctx)?.let { RowOutcome.Suppressed(it) } ?: RowOutcome.Passed
            }
        }
    }

    /** The first referenced field that is formally invalid in [ctx] (a malformed value or required-empty), else null. */
    private fun firstSuppressor(refs: List<String>, ctx: EvalContext): Suppression? =
        refs.firstNotNullOfOrNull { path -> formalErrorAt(path, ctx)?.let { Suppression(path, it) } }

    /**
     * The formal-error code of the field instance at [path] in [ctx], or null when well-formed. A present
     * value is checked for its format ([formalError]); an empty value is checked for requiredness
     * ([requiredEmpty] → [MANDATORY_FIELD]). This is the single source for "is this operand unknown?" — fed
     * to the [Interpreter] (resolve to Val.Unknown) and to [firstSuppressor] (name the suppressor).
     */
    private fun formalErrorAt(path: String, ctx: EvalContext): String? {
        val raw = ctx.displayValue(path)
        if (raw != null) {
            val kind = model.kinds[path] ?: return null
            return formalError(kind, raw, model.scaleOf(path))?.id
        }
        return if (requiredEmpty(path, ctx)) MANDATORY_FIELD else null
    }

    /**
     * A rule is evaluated per row of its iteration scope — the **deepest repeatable group** that is an
     * ancestor of any *non-aggregated* field reference in the condition (a starred ref is an aggregate
     * and collapses the repetition, so it does NOT force iteration). With no repeatable ref, the scope is
     * the rule's declared [RuleDef.group]. This implements the kernel law (KERNEL-FINDINGS §9 supplement,
     * `IterationLawsTest`): the iteration scope is the set of *referenced* repeatable fields, NOT the
     * rule's node placement — so a rule declared at `/Order` that reaches into `/Order/Items` iterates the
     * Items rows.
     */
    private fun iterationScope(rule: RuleDef, ast: Cond): String =
        scopeFromRefs(nonStarredRefs(ast), rule.group)

    /** The deepest repeatable group ancestor of any [refs] path, else [defaultGroup]. */
    private fun scopeFromRefs(refs: List<String>, defaultGroup: String): String =
        refs.flatMap { ref -> ancestorGroups(ref).filter { it in model.repeatable } }
            .maxByOrNull { it.count { c -> c == '/' } }
            ?: defaultGroup

    /** Every non-starred field-reference path in the condition (Star refs are aggregates, excluded). */
    private fun nonStarredRefs(cond: Cond): List<String> = when (cond) {
        is Cond.Cmp -> exprRefs(cond.left) + exprRefs(cond.right)
        is Cond.And -> nonStarredRefs(cond.left) + nonStarredRefs(cond.right)
        is Cond.Or -> nonStarredRefs(cond.left) + nonStarredRefs(cond.right)
        is Cond.Not -> nonStarredRefs(cond.c)
        is Cond.Pred -> cond.args.flatMap { exprRefs(it) }
        is Cond.Pattern -> exprRefs(cond.value) + exprRefs(cond.pattern)
    }

    private fun exprRefs(expr: Expr): List<String> = when (expr) {
        is Expr.Field -> listOf(expr.path)
        is Expr.Func -> expr.args.flatMap { exprRefs(it) }   // Star args excluded → drop through
        is Expr.Arith -> exprRefs(expr.left) + exprRefs(expr.right)
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
}
