package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Property 1 — Lazy ≡ eager polarity** (proposal §8): the production lazy-polarity walk (`evalTypedLazy`, behind
 * `firedType`) agrees with the full eager typed walk (`evalTyped`) on every sampled (condition, document, row) —
 * the same truth, and the same VALUE/OMISSION polarity when the rule fires. This is a *trusted KDoc induction* in
 * the engine; the property makes it a swept law. Uses the internal [Interpreter.lazyEagerConsistent] seam over the
 * authored condition catalog × [PropertySupport.SEEDS]; runs on JVM + JS.
 */
class LazyEagerPolarityPropertyTest {

    @Test
    fun theLazyWalkAgreesWithTheEagerWalkOnEverySample() {
        var checks = 0
        val failures = mutableListOf<String>()
        for (a in PropertySupport.authored) {
            val cond = parseCondition(a.text, a.group)
            for (seed in PropertySupport.SEEDS) {
                for (ctx in a.scenario.document(seed).contextsFor(a.group)) {
                    checks++
                    if (!Interpreter(ctx, indexFields = a.scenario.model.indexFields).lazyEagerConsistent(cond)) {
                        failures += "${a.scenario.name} '${a.text}' seed=$seed"
                    }
                }
            }
        }
        assertTrue(checks > 0, "no lazy/eager checks ran — the sample is vacuous")
        assertTrue(failures.isEmpty(),
            "lazy ≠ eager on ${failures.size} of $checks samples:\n  " + failures.take(10).joinToString("\n  "))
    }
}
