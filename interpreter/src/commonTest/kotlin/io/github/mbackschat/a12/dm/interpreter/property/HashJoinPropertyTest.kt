package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Property 6 — Hash-join ≡ nested-loop** (proposal §8): the `$`-equality-correlation **fast path** (the O(1)
 * hash-join over buckets the `Document` caches) computes the exact same `(t, f, n)` tally as the naive O(N²) inner
 * scan, on every sampled input. Uses a duplicate-key self-correlation over the single-repeatable model — whose
 * index keys are drawn from a 3-value pool, so collisions (the interesting bucket case) are common — and the
 * internal [Interpreter.correlationTallyBothWays] seam, which returns both tallies for a recognized correlation
 * `Having`. Sweeps [PropertySupport.SEEDS] on JVM + JS.
 */
class HashJoinPropertyTest {

    // "another Lines row shares this row's ItemId and has an Amount" — the self-excluded equality self-correlation
    // that takes the hash-join fast path. Authored relative to the rule group /Cart (Having paths as `Lines/…`).
    private val condText =
        "AtLeastOneFieldFilled(Lines*/Amount Having [Lines/ItemId] == [\$Lines/ItemId] " +
            "And CurrentRepetition(Lines) != CurrentRepetition(\$Lines))"

    @Test
    fun theHashJoinEqualsTheNaiveScanOnEverySample() {
        val model = PropertySupport.singleRep.model
        val star = correlatedStar(parseCondition(condText, "/Cart"))
            ?: error("no correlated star parsed from: $condText")
        var checks = 0
        val failures = mutableListOf<String>()
        for (seed in PropertySupport.SEEDS) {
            for (ctx in PropertySupport.singleRep.document(seed).contextsFor("/Cart/Lines")) {
                val both = Interpreter(ctx, EvalConfig(indexFields = model.indexFields)).correlationTallyBothWays(star) ?: continue
                checks++
                if (both.first != both.second) failures += "seed=$seed hash=${both.first} naive=${both.second}"
            }
        }
        assertTrue(checks > 0, "no correlated outer rows were sampled — the property is vacuous")
        assertTrue(failures.isEmpty(),
            "hash-join ≠ naive scan on ${failures.size} of $checks:\n  " + failures.take(10).joinToString("\n  "))
    }

    /** The first starred operand carrying a `Having` filter in [cond] (the correlated aggregate). */
    private fun correlatedStar(cond: Cond): Expr.Star? {
        fun inExpr(e: Expr): Expr.Star? = when (e) {
            is Expr.Star -> if (e.having != null) e else null
            is Expr.Func -> e.args.firstNotNullOfOrNull(::inExpr)
            is Expr.Arith -> inExpr(e.left) ?: inExpr(e.right)
            else -> null
        }
        fun inCond(c: Cond): Expr.Star? = when (c) {
            is Cond.Cmp -> inExpr(c.left) ?: inExpr(c.right)
            is Cond.Tolerance -> inExpr(c.left) ?: inExpr(c.right)
            is Cond.ValueListQuant -> (c.fields + c.values).firstNotNullOfOrNull(::inExpr)
            is Cond.And -> inCond(c.left) ?: inCond(c.right)
            is Cond.Or -> inCond(c.left) ?: inCond(c.right)
            is Cond.Not -> inCond(c.c)
            is Cond.Pred -> c.args.firstNotNullOfOrNull(::inExpr)
            is Cond.Pattern -> inExpr(c.value) ?: inExpr(c.pattern)
            is Cond.Custom -> null
        }
        return inCond(cond)
    }
}
