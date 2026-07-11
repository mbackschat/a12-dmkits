package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Kleene
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Property 2 — Kleene monotonicity / suppression soundness** (proposal §8): routing any operand to `UNKNOWN`
 * never flips the outcome between `TRUE` and `FALSE` — it can only lose information (the result becomes the same
 * value or `UNKNOWN`). Branch-scoped suppression is then nothing but this monotonicity: a formal error on a
 * healthy `Or`'s losing branch keeps the win (`T ∨ U = T`), while on an `And`'s branch it suppresses
 * (`T ∧ U = U`). Two layers:
 *
 * 1. the foundational [Kleene] table — exhaustively, `and`/`or`/`not` with an operand forced to `UNKNOWN`;
 * 2. the engine — routing each referenced field leaf to `UNKNOWN` (the interpreter's real suppression mechanism,
 *    the `invalid` predicate) over the authored conditions × sampled documents, asserting the firing never flips.
 *
 * Scope: the **counting quantifiers** (`NoFieldFilled`/`NotAllFieldsFilled`, `kleeneMonotone = false`) are
 * excluded here — "operand UNKNOWN" there is a *tally* change (an operand dropped from a count), not a 3VL leaf
 * going UNKNOWN, so Kleene monotonicity is not the applicable law; they are still swept by property 1. Runs on
 * JVM + JS.
 */
class KleeneMonotonicityPropertyTest {

    @Test
    fun theKleeneTableIsMonotoneTowardUnknown() {
        for (a in Tri.entries) for (b in Tri.entries) {
            noFlip(Kleene.and(a, b), Kleene.and(Tri.UNKNOWN, b))
            noFlip(Kleene.and(a, b), Kleene.and(a, Tri.UNKNOWN))
            noFlip(Kleene.or(a, b), Kleene.or(Tri.UNKNOWN, b))
            noFlip(Kleene.or(a, b), Kleene.or(a, Tri.UNKNOWN))
        }
        for (a in Tri.entries) noFlip(Kleene.not(a), Kleene.not(Tri.UNKNOWN))
    }

    @Test
    fun routingAnOperandToUnknownNeverFlipsTheFiring() {
        var checks = 0
        val failures = mutableListOf<String>()
        for (a in PropertySupport.authored.filter { it.kleeneMonotone }) {
            val cond = parseCondition(a.text, a.group)
            val fields = PropertySupport.fieldPathsOf(cond)
            val indexFields = a.scenario.model.indexFields
            for (seed in PropertySupport.SEEDS) {
                for (ctx in a.scenario.document(seed).contextsFor(a.group)) {
                    val base = Interpreter(ctx, EvalConfig(indexFields = indexFields)).eval(cond)
                    for (p in fields) {
                        checks++
                        val routed = Interpreter(ctx, EvalConfig(indexFields = indexFields), invalid = { it == p }).eval(cond)
                        if (routed != base && routed != Tri.UNKNOWN) {
                            failures += "${a.scenario.name} '${a.text}' route=$p seed=$seed base=$base routed=$routed"
                        }
                    }
                }
            }
        }
        assertTrue(checks > 0, "no routing checks ran — the sample is vacuous")
        assertTrue(failures.isEmpty(),
            "routing to UNKNOWN flipped the firing on ${failures.size} of $checks:\n  " + failures.take(10).joinToString("\n  "))
    }

    /** Routing an operand to UNKNOWN yields the same value or UNKNOWN — never the opposite definite value. */
    private fun noFlip(base: Tri, routed: Tri) =
        assertTrue(routed == base || routed == Tri.UNKNOWN, "flip: base=$base routed=$routed")
}
