package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef

/**
 * The `RepetitionNotUnique` evaluator — a call-invariant collaborator of the I2 eval-state seam (IF117).
 * [recognize] classifies a rule's condition as the per-row-predicate-under-And shape (strict — degrade
 * loud, IF61), and [messages] emits the duplicate-row firings over one [ValidationPass] (the guard rides
 * the pass's per-row interpreter; the key-cell exclusion rides its formal-status memo). Allocated once per
 * [DmInterpreter].
 */
internal class RepetitionNotUniqueEvaluator(private val model: ModelDef, private val scopes: IterationScopes) {

    /** A recognized `RepetitionNotUnique` rule: its key field paths, the optional `@From` reference-group ref,
     *  and the optional [guard] — the conjunction of the OTHER top-level conjuncts when the RNU predicate is
     *  one conjunct of an And-tree (null when RNU is the whole condition). */
    data class RnuRule(val keys: List<String>, val fromScope: String?, val guard: Cond?)

    /**
     * If [rule]'s condition is `[<guard> And] RepetitionNotUnique(key, … [@From ref])` — the RNU predicate
     * standing alone OR as exactly ONE top-level conjunct of an And-tree — its keys, the optional `@From` ref,
     * and the guard (the AND of the remaining conjuncts, or null); else null (a normal rule, handled per-row).
     *
     * The kernel evaluates RepetitionNotUnique as a **per-row 3VL predicate** whose duplicate cache is built
     * over the full scope **independent of the guard** — confirmed in BOTH strategies: the runtime
     * `RuntimeController.wiederholungNichtEindeutig` builds the cache once (`isInitialised`) over all rows in
     * `ermittleWiederholungenMitDuplikaten`, then per-row returns TRUE_WF/TRUE_AF/FALSE_OR_UNKNOWN; the codegen
     * `EntityListCondition.st` emits a call to that same method with a per-rule `RepetitionNotUniqueErrorCache`.
     * So a guard is just a separate conjunct combined by the ordinary 3VL And ([messages]
     * gates each duplicate row by it).
     *
     * Recognition stays STRICT — degrade loud (IF61), never silently wrong — returning null when the shape is
     * NOT this per-row-predicate-under-And form: RNU under an `Or` or nested below a non-`And` (never appears as
     * a top-level conjunct → 0 found), more than one RNU conjunct, a group/asterisk (non-`Expr.Field`) key (the
     * kernel forbids those), or a guard whose iteration scope is DEEPER than the keys' group (an iteration
     * granularity we don't model). None of these occurs in the real corpora; each is handled loud not wrong.
     */
    fun recognize(rule: RuleDef, cond: Cond): RnuRule? {
        val conjuncts = flattenAnd(cond)
        val rnuPreds = conjuncts.filter { it is Cond.Pred && it.name == "RepetitionNotUnique" }
        if (rnuPreds.size != 1) return null                              // 0 → not an RNU rule; >1 → unmodeled
        val pred = rnuPreds[0] as Cond.Pred
        val guards = conjuncts.filter { it !== pred }
        if (guards.any { containsRepetitionNotUnique(it) }) return null  // RNU nested in another conjunct — unmodeled
        // The parser appends an `@From:<ref>` StrLit marker as the last arg when `@From` is present.
        val fromScope = (pred.args.lastOrNull() as? Expr.StrLit)?.v?.takeIf { it.startsWith("@From:") }?.removePrefix("@From:")
        val keyArgs = if (fromScope != null) pred.args.dropLast(1) else pred.args
        val keys = keyArgs.map { (it as? Expr.Field)?.path ?: return null }.ifEmpty { return null }
        val guard = guards.reduceOrNull { a, b -> Cond.And(a, b) }
        if (guard != null && !guardScopeWithinKeyGroup(guard, rule, keys.first())) return null
        return RnuRule(keys, fromScope, guard)
    }

    /** Flatten a top-level `And`-tree into its conjuncts; a non-`And` node is a single conjunct. */
    private fun flattenAnd(cond: Cond): List<Cond> =
        if (cond is Cond.And) flattenAnd(cond.left) + flattenAnd(cond.right) else listOf(cond)

    /** Whether a `RepetitionNotUnique` predicate appears anywhere within [cond] (any nesting/boolean position). */
    private fun containsRepetitionNotUnique(cond: Cond): Boolean = when (cond) {
        is Cond.Pred -> cond.name == "RepetitionNotUnique"
        is Cond.And -> containsRepetitionNotUnique(cond.left) || containsRepetitionNotUnique(cond.right)
        is Cond.Or -> containsRepetitionNotUnique(cond.left) || containsRepetitionNotUnique(cond.right)
        is Cond.Not -> containsRepetitionNotUnique(cond.c)
        else -> false
    }

    /** The guard's iteration scope must be the keys' repeatable group or ABOVE it, so each key row uniquely
     *  determines the guard's row (RNU iterates the key group; a deeper/sibling guard would need a different
     *  iteration granularity — unmodeled, degrade loud). */
    private fun guardScopeWithinKeyGroup(guard: Cond, rule: RuleDef, firstKey: String): Boolean {
        val keyGroup = parentGroup(firstKey)
        val guardScope = scopes.scopeFromRefs(nonStarredRefs(guard), rule.group)
        return keyGroup == guardScope || keyGroup.startsWith("$guardScope/")
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
    fun messages(rule: RuleDef, rnu: RnuRule, pass: ValidationPass): List<Message> {
        val keys = rnu.keys
        val repGroup = parentGroup(keys.first())     // the keys' shared repeatable group (kernel: keys on one path)
        val errorPath = resolveErrorPath(rule)
        val scopeLen = scopePrefixLen(rule, rnu)
        data class Row(val ctx: EvalContext, val reps: List<Int>, val key: List<String?>)
        val rows = pass.doc.contextsFor(repGroup).mapNotNull { ctx ->
            // A key cell that is FORMALLY INVALID (kernel `isWertAngegeben == UNKNOWN`) drops the WHOLE row from
            // the duplicate cache — exactly the kernel's getWerteOrNullIfEinFeldNichtPruefungsRelevant (any UNKNOWN
            // key cell → returns null → the row is `continue`-skipped). A cell is UNKNOWN whenever it is marked
            // `fehlerhaft` (not check-relevant), which is EVERY [formalErrorAt] cause: a bad FORMAT, a custom-type
            // rejection, a duplicate-INDEX (`uniqueIndex`) cell, an over-repetition cell, AND a required-EMPTY
            // cell — the kernel's mandatory check is a PRELIMINARY autogen rule (`isPreliminary` → `preliminaryError`
            // → `markFieldAsInvalidForValidation`) that runs BEFORE the RNU rule builds its cache, so a required-
            // empty key cell is UNKNOWN, not a valid empty participant. (This corrects IF64's original
            // MANDATORY_FIELD carve-out — the composite-key required-empty differential proved the kernel drops
            // the row; IF64 corrected 2026-07-07.) Contrast an OPTIONAL-empty key cell: NOT required → `formalErrorAt` is null →
            // `isWertAngegeben == FALSE` → a null key component → a valid empty participant (the OMISSION case).
            // Exclusion is per-KEY-CELL: a formal error on a NON-key field of the same row does not drop it (the
            // kernel checks only the key `entityList`).
            if (keys.any { k -> pass.formalErrorAt(k, ctx.repsOf(k), ctx) != null })
                return@mapNotNull null
            Row(ctx, ctx.repsOf(errorPath), keys.map { ctx.displayValue(it)?.takeIf { v -> v.isNotEmpty() } })
        }
        val out = mutableListOf<Message>()
        rows.filter { row -> row.key.any { it != null } }      // participant: at least one key field is filled
            // Partial validation: only RELEVANT rows participate in duplicate detection — the kernel builds its
            // duplicate cache from the relevant fields only, so a relevant row whose duplicate PARTNER is not
            // relevant is NOT flagged (IF39, the sibling of the uniqueIndex case). Full: every row (ALL_RELEVANT).
            .filter { row -> pass.isRelevant(errorPath, row.reps) }
            .groupBy { it.reps.take(scopeLen) }                // scope: per reference-group parent row (basic version)
            .forEach { (_, scope) ->
                val dups = scope.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
                for (row in scope.filter { it.key in dups }) {
                    // RepetitionNotUnique's own polarity: OMISSION when a (fillable) key component is empty, else VALUE.
                    val rnuType = if (row.key.any { it == null }) MsgType.OMISSION else MsgType.VALUE
                    // A combined `<guard> And RepetitionNotUnique(key)`: the guard is a SEPARATE per-row 3VL
                    // conjunct (the duplicate cache above is guard-independent, matching the kernel). The And fires
                    // only if the guard also fires, and its polarity combines by the Cond.And rule (OMISSION if
                    // either operand is OMISSION). A guard that is FALSE/UNKNOWN for this row suppresses it.
                    val type = if (rnu.guard == null) rnuType else {
                        val guardType = pass.interpreterFor(row.ctx, errorPath)
                            .firedType(rnu.guard) ?: continue
                        if (rnuType == MsgType.OMISSION || guardType == MsgType.OMISSION) MsgType.OMISSION else MsgType.VALUE
                    }
                    val text = rule.messages[pass.locale]?.let { renderMessage(it, rule.group, row.ctx) { p -> model.labelOf(p, pass.locale) } } ?: ""
                    val referenced = keys.map { formatPointer(it, row.ctx.repsOf(it)) }
                    val fillToFix = if (type == MsgType.OMISSION) referenced else emptyList()
                    out += Message(rule.errorCode, rule.path, formatPointer(errorPath, row.reps),
                        rule.severity, type, text, referenced, fillToFix)
                }
            }
        return out
    }
}
