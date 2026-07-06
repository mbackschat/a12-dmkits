package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Property 7 — Determinism** (proposal §8): `validateFull` twice ≡ once (and `compute` twice ≡ once).
 * Evaluation is a pure function of (model, document): repeating it — on the SAME reused engine, and on a FRESH
 * engine — yields the identical messages (order included), unsupported reports, and computed outcomes. This
 * guards the shared caches (parsed-AST, per-row memos) against introducing run-to-run drift. Sweeps every
 * [PropertySupport.scenarios] model × [PropertySupport.SEEDS] on JVM and JS.
 */
class DeterminismPropertyTest {

    @Test
    fun validateAndComputeAreDeterministic() {
        for (scenario in PropertySupport.scenarios) {
            val reused = DmInterpreter(scenario.model)
            for (seed in PropertySupport.SEEDS) {
                val doc = scenario.document(seed)
                val where = "${scenario.name} seed=$seed"

                val v1 = reused.validate(doc)
                val v2 = reused.validate(doc)
                assertEquals(v1.messages, v2.messages, "$where: validate messages differ across runs")
                assertEquals(v1.unsupported, v2.unsupported, "$where: validate unsupported differ across runs")

                // A FRESH engine (cold caches) must agree with the reused one — the caches are memoization only.
                val fresh = DmInterpreter(scenario.model).validate(doc)
                assertEquals(v1.messages, fresh.messages, "$where: fresh engine disagrees with reused")

                assertEquals(reused.compute(doc), reused.compute(doc), "$where: compute differs across runs")
            }
        }
    }
}
