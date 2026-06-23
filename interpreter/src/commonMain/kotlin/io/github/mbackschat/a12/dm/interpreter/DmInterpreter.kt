package io.github.mbackschat.a12.dm.interpreter

/**
 * The public entry point — evaluates a [ModelDef]'s rules over a [Document] instance, kernel-free, on
 * JVM and JS alike. The standalone-library façade (proposal goal 2) and the engine dmtool's native
 * image consumes for the runtime verbs (goal 1). Correctness = agrees with the real kernel (verified by
 * the `:adapter` differential tests).
 *
 * Today: full validation ([validateFull]) and single-rule eval ([testRule]/[evalCandidate]). Partial
 * validation, computations, and formal-error suppression are the next increments (INTERPRETER-BUILDOUT.md).
 */
class DmInterpreter(private val model: ModelDef) {

    /**
     * Run every rule over each row of its iteration scope and return one [Message] per firing — the
     * runtime twin of the kernel's `validateFull`. A rule fires when its error condition is TRUE on a row.
     */
    fun validateFull(doc: Document, locale: String = "en_US"): List<Message> =
        model.rules.flatMap { messagesFor(it, doc, locale) }

    /**
     * Evaluate a SINGLE stored rule against the instance — the `rule test` shape. [Verdict.FIRED] (a
     * violation, with the rendered [RuleResult.message]) / [Verdict.PASSED] (evaluated, did not fire).
     * [Verdict.SUPPRESSED] (a referenced field is formally invalid) awaits the formal-error increment.
     */
    fun testRule(rulePath: String, doc: Document, locale: String = "en_US"): RuleResult {
        val rule = model.rules.firstOrNull { it.path == rulePath }
            ?: throw IllegalArgumentException("no such rule: $rulePath")
        return evalRule(rule, doc, locale)
    }

    /** Evaluate an injected, NON-stored candidate rule (the `model eval --condition/--field` shape). */
    fun evalCandidate(rule: RuleDef, doc: Document, locale: String = "en_US"): RuleResult =
        evalRule(rule, doc, locale)

    private fun evalRule(rule: RuleDef, doc: Document, locale: String): RuleResult {
        val hit = messagesFor(rule, doc, locale).firstOrNull()
        return if (hit != null) RuleResult(rule.path, Verdict.FIRED, hit.text, hit.type)
        else RuleResult(rule.path, Verdict.PASSED)
    }

    /** Every firing of one rule across its iteration-scope rows, as [Message]s (empty = did not fire). */
    private fun messagesFor(rule: RuleDef, doc: Document, locale: String): List<Message> {
        val ast = parseCondition(rule.conditionText, rule.group)
        return doc.contextsFor(iterationScope(rule, ast)).mapNotNull { ctx ->
            val interp = Interpreter(ctx)
            if (!interp.fires(ast)) return@mapNotNull null
            val type = interp.firedType(ast) ?: MsgType.VALUE
            val text = rule.messages[locale]?.let { renderMessage(it, rule.group, ctx) } ?: ""
            Message(rule.errorCode, rule.path, errorPath(rule, ctx), rule.severity, type, text)
        }
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
        nonStarredRefs(ast)
            .flatMap { ref -> ancestorGroups(ref).filter { it in model.repeatable } }
            .maxByOrNull { it.count { c -> c == '/' } }
            ?: rule.group

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
        else -> emptyList()
    }

    private fun ancestorGroups(fieldPath: String): List<String> {
        val parts = fieldPath.trim('/').split('/')
        return (1 until parts.size).map { "/" + parts.take(it).joinToString("/") }
    }

    /** Resolve the rule's error-entity relative path (`../Field`) against its group to an absolute path. */
    private fun errorPath(rule: RuleDef, ctx: EvalContext): String {
        var base = rule.group
        var rel = rule.errorEntityRelPath
        while (rel.startsWith("../")) {
            base = base.substringBeforeLast('/', "")
            rel = rel.removePrefix("../")
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}
