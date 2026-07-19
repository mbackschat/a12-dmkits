package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef

/**
 * The `RepetitionNotUnique` duplicate-cache builder — a call-invariant collaborator of the I2 eval-state seam
 * (IF117). RNU is an **ordinary per-row 3VL leaf** (IF149): the kernel's
 * `RuntimeController.wiederholungNichtEindeutig` returns a plain `ValidierungsErgebnis` (TRUE_WF / TRUE_AF /
 * FALSE_OR_UNKNOWN) whose duplicate cache is built ONCE over the full scope (`isInitialised`) **independent of
 * the surrounding boolean context**, then per-row returns VALUE / OMISSION / not-fired. So RNU composes under
 * arbitrary legal `And`/`Or`/`Not` exactly like any other leaf — this builder produces the per-row outcome map
 * the interpreter's [io.github.mbackschat.a12.dm.interpreter.eval.Interpreter] leaf reads, and the ordinary
 * boolean walk does the composition (no positional recognizer, no separate message emitter).
 *
 * [outcomes] scans the keys' repeatable group ONCE per pass and returns the dup rows' polarity, keyed by the
 * error-path reps the leaf looks up. Allocated once per [DmInterpreter].
 */
internal class RepetitionNotUniqueEvaluator(private val model: ModelDef, private val scopes: IterationScopes) {

    /** A recognized `RepetitionNotUnique` predicate: its key field paths and the optional `@From` reference-group
     *  ref. The boolean position is irrelevant — RNU is a plain 3VL leaf composed by the ordinary walk. */
    private data class RnuKeys(val keys: List<String>, val fromScope: String?)

    /**
     * The per-row RNU outcome map for [rule]'s condition — `errorPathReps → MsgType` for each duplicate row (the
     * kernel's `RepetitionNotUniqueErrorCache`: VALUE when every key component is present, OMISSION when a
     * (fillable) key component is empty). Returns **null** when [cond] contains no `RepetitionNotUnique` predicate
     * (a normal rule — no cache needed). The map is guard-independent: it scans ALL rows of the keys' repeatable
     * group, exactly like the kernel's once-built cache, and the surrounding `And`/`Or` composition is applied by
     * the ordinary boolean walk over this leaf.
     *
     * Degrades LOUD ([UnsupportedConstructException] → the rule is reported unsupported, never silently wrong) for
     * the shapes the interpreter does not model: MORE THAN ONE RNU predicate in a rule (kernel-invalid), a
     * group/asterisk (non-`Expr.Field`) key (the kernel forbids those), or an iteration scope DEEPER than the
     * keys' group (an iteration granularity we don't model — none occurs in the real corpora).
     */
    fun outcomes(rule: RuleDef, cond: Cond, pass: ValidationPass): Map<List<Int>, MsgType>? {
        val rnu = findRnu(cond) ?: return null
        val keys = rnu.keys
        val keyGroup = parentGroup(keys.first())     // the keys' shared repeatable group (kernel: keys on one path)
        // The iteration scope must be the keys' group or ABOVE it — a guard/branch reaching a DEEPER group would
        // need a different iteration granularity (unmodeled; the pre-IF149 recognizer declined it too).
        val scope = scopes.scopeFromRefs(nonStarredRefs(cond), rule.group)
        if (keyGroup != scope && !keyGroup.startsWith("$scope/")) {
            throw UnsupportedConstructException(
                "RepetitionNotUnique with an iteration scope ($scope) deeper than its key group ($keyGroup) is not modeled")
        }
        val errorPath = resolveErrorPath(rule)
        val scopeLen = scopePrefixLen(rule, rnu)
        data class Row(val reps: List<Int>, val key: List<String?>)
        val rows = pass.doc.contextsFor(keyGroup).mapNotNull { ctx ->
            // A key cell that is FORMALLY INVALID (kernel `isWertAngegeben == UNKNOWN`) drops the WHOLE row from
            // the duplicate cache — the kernel's getWerteOrNullIfEinFeldNichtPruefungsRelevant (any UNKNOWN key
            // cell → null → the row is skipped). UNKNOWN covers a bad FORMAT, a custom-type rejection, a
            // duplicate-INDEX cell, an over-repetition cell, AND a required-EMPTY cell (the preliminary mandatory
            // rule marks it invalid before RNU builds its cache — IF64, corrected 2026-07-07). An OPTIONAL-empty
            // key cell is NOT invalid: it is a valid empty participant (the OMISSION case). Exclusion is per-KEY-
            // CELL: a formal error on a NON-key field of the same row does not drop it.
            if (keys.any { k -> pass.formalErrorAt(k, ctx.repsOf(k), ctx) != null }) return@mapNotNull null
            Row(ctx.repsOf(errorPath), keys.map { ctx.displayValue(it)?.takeIf { v -> v.isNotEmpty() } })
        }
        val out = HashMap<List<Int>, MsgType>()
        rows.filter { row -> row.key.any { it != null } }      // participant: at least one key field is filled
            // Partial validation: only RELEVANT rows participate in duplicate detection — the kernel builds its
            // duplicate cache from the relevant fields only, so a relevant row whose duplicate PARTNER is not
            // relevant is NOT flagged (IF39). Full: every row (ALL_RELEVANT).
            .filter { row -> pass.isRelevant(errorPath, row.reps) }
            .groupBy { it.reps.take(scopeLen) }                // scope: per reference-group parent row (basic version)
            .forEach { (_, scopeRows) ->
                val dups = scopeRows.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
                for (row in scopeRows.filter { it.key in dups }) {
                    // RNU's own polarity: OMISSION when a (fillable) key component is empty, else VALUE. The
                    // surrounding And/Or combines this with any guard/branch via the ordinary 3VL walk.
                    out[row.reps] = if (row.key.any { it == null }) MsgType.OMISSION else MsgType.VALUE
                }
            }
        return out
    }

    /** The single `RepetitionNotUnique` predicate anywhere in [cond] (its keys + `@From`), or null when absent. */
    private fun findRnu(cond: Cond): RnuKeys? {
        val preds = collectRnuPreds(cond)
        if (preds.isEmpty()) return null
        if (preds.size > 1) throw UnsupportedConstructException(
            "more than one RepetitionNotUnique predicate in a rule is not modeled")
        val pred = preds[0]
        // The parser appends an `@From:<ref>` StrLit marker as the last arg when `@From` is present.
        val fromScope = (pred.args.lastOrNull() as? Expr.StrLit)?.v?.takeIf { it.startsWith("@From:") }?.removePrefix("@From:")
        val keyArgs = if (fromScope != null) pred.args.dropLast(1) else pred.args
        val keys = keyArgs.map {
            (it as? Expr.Field)?.path
                ?: throw UnsupportedConstructException("RepetitionNotUnique key must be a plain field, got $it")
        }
        if (keys.isEmpty()) throw UnsupportedConstructException("RepetitionNotUnique needs at least one key field")
        return RnuKeys(keys, fromScope)
    }

    /** Every `RepetitionNotUnique` predicate in [cond] (any nesting/boolean position). */
    private fun collectRnuPreds(cond: Cond): List<Cond.Pred> = when (cond) {
        is Cond.Pred -> if (cond.name == "RepetitionNotUnique") listOf(cond) else emptyList()
        is Cond.And -> collectRnuPreds(cond.left) + collectRnuPreds(cond.right)
        is Cond.Or -> collectRnuPreds(cond.left) + collectRnuPreds(cond.right)
        is Cond.Not -> collectRnuPreds(cond.c)
        else -> emptyList()
    }

    /**
     * The scope prefix length: the rep-prefix length of the **reference group's PARENT** row. The reference
     * group is the `@From` ref when given ([rnu].fromScope), else the **basic version** default — the first
     * repeatable group below the rule group on the key path (BA "basic version", Situation 2). So a key two
     * repeatable levels deep (`/Project/Milestones/Tasks/Effort`, rule at `/Project`) is by default checked
     * across ALL milestones (scope = the `/Project` row); `@From Milestones/Tasks` narrows it to per-milestone.
     */
    private fun scopePrefixLen(rule: RuleDef, rnu: RnuKeys): Int {
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
}
