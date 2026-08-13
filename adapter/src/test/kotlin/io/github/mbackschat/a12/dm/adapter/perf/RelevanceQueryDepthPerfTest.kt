package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * **`validatePart`'s relevance query must be bounded by the relevant set the caller supplied, never by the
 * model's DEPTH.** Enumerating `2^depth` wildcard/concrete combinations per query is exponential in a quantity
 * the model contract does not bound at single digits — `ModelPreparation` admits depth 64 by default and 128 as
 * its hard limit — so the assertion "depth is single digits" is exactly what let the defect ship.
 *
 * **Moved here from `:interpreter`'s `PartialRelevanceDepthTest` (2026-08-13, IF295), and strengthened on the
 * way.** It was `elapsed < 300` ms in a module whose tests run in no CI lane and carry no tier, so it was a
 * clock-dependent verdict nothing isolated. The absolute bound is also the weaker instrument: it answers "was
 * this machine fast enough", when the claim is "cost does not grow with depth". Growth across two depths is
 * machine-independent — bounded by the relevant set the ratio is ~linear in depth, while an exponential at
 * 12 → 24 is `2^12 = 4096`x, so the standard catastrophe floor sits ~450x below the defect and cannot be reached
 * by contention. The correctness half of the original test, which the index broke silently at the Int shift
 * boundary, stays in `:interpreter` where it belongs.
 */
@Tag("perf")
class RelevanceQueryDepthPerfTest {

    @Test
    fun theRelevanceQueryDoesNotEnumerateCombinationsOfTheModelDepth() {
        // Prepared outside the measurement: only the query is under test, not fixture construction.
        val prepared = DEPTHS.associateWith { depth ->
            val def = nestedModel(depth)
            val doc = instance(def, depth)
            val relevant = deepOnlyRelevant(depth)
            val interpreter = DmInterpreter(def)
            interpreter.validatePart(doc, relevant)      // prime: parse + plan
            Triple(interpreter, doc, relevant)
        }

        val accepted = PerfHarness.assertSubQuadraticLane(
            label = "relevance query over model depth",
            small = DEPTHS.first(),
            large = DEPTHS.last(),
            identityAt = { depth -> "nested non-repeatable model depth=$depth, deep-only relevant set" },
            culprit = "the relevance query enumerating wildcard/concrete combinations of the DEPTH instead of " +
                "probing the patterns the caller's relevant set actually contains",
        ) { depth ->
            val (interpreter, doc, relevant) = prepared.getValue(depth)
            PerfHarness.measureCurrentThreadCpu(warmup = 0, iters = REPEATS) {
                repeat(BATCH) { interpreter.validatePart(doc, relevant) }
            }.medianMs
        }

        // A ratio over two unmeasurably small numbers passes no matter what the engine did. The correct query is
        // milliseconds at these depths, so a measurement that rounds to zero means the clock, not the code, was
        // what answered.
        assertTrue(
            accepted.smallMs > 0.0,
            "the depth-${DEPTHS.first()} measurement was ${accepted.smallMs}ms — too small to carry a ratio, so " +
                "this run asserted nothing about growth",
        )
    }

    private companion object {
        const val CODE = "DEEP"
        const val REPEATS = 3

        /**
         * Calls per timed block. One correct query at these depths costs ~0.02 ms, and the absolute anchor carries
         * a **1 ms** slack term so a sub-millisecond lane cannot flap on a fraction of a millisecond — which would
         * have left this lane's anchor guarding "under 1.06 ms" against a 0.06 ms observation, decorative rather
         * than a bar. Batching lifts the recorded median clear of the slack without changing what is measured: the
         * growth ratio is a quotient, so a constant factor on both scales cancels exactly.
         */
        const val BATCH = 50

        /** Doubling the depth multiplies an exponential query's probes by `2^12`, and a bounded one by ~2. */
        val DEPTHS = listOf(12, 24)

        /** `/G1/G2/.../G{depth-1}/F` — [depth] path parts, the field's own included. */
        fun fieldPath(depth: Int): String = (1 until depth).joinToString("") { "/G$it" } + "/F"

        fun groupPath(depth: Int): String = (1 until depth).joinToString("") { "/G$it" }

        /**
         * The INNERMOST group plus the field, wildcarded — deliberately not every ancestor. The query walks
         * prefixes from the root and stops at its first match, so a wildcard at depth 1 answers immediately and
         * no deep coordinate arithmetic ever runs; only a set whose shallowest match is itself deep reaches it.
         * The field alone is not enough either: the rule is gated on its group as well as its error cell.
         */
        fun deepOnlyRelevant(depth: Int): Set<RelevantEntity> = setOf(
            RelevantEntity.anyRep(groupPath(depth)),
            RelevantEntity.anyRep(fieldPath(depth)),
        )

        /** Non-repeatable nesting: the cost is in the coordinate arithmetic, which every path part feeds. */
        fun nestedModel(depth: Int): ModelDef {
            val field = fieldPath(depth)
            val group = groupPath(depth)
            return ModelDef(
                listOf(FieldDef(field, Kind.STRING)),
                listOf(RuleDef("$group/R", group, "FieldFilled(F)", CODE, "F")),
                groupPaths = (1 until depth).map { d -> (1..d).joinToString("") { "/G$it" } }.toSet(),
            )
        }

        fun instance(def: ModelDef, depth: Int) = def.newDocument().also { doc ->
            for (d in 1 until depth) {
                doc.group((1..d).joinToString("") { "/G$it" }, *IntArray(d) { 1 })
            }
            doc.field(fieldPath(depth), "x", *IntArray(depth) { 1 })
        }
    }
}
