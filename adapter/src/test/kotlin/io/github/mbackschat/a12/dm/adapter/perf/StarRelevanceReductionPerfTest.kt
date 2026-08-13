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
 * The wildcard-dominance reduction behind the all-rows aggregate relevance gate ([IF262]) costs
 * `entities x distinctWildcardPatterns x depth`. The pattern count is bounded by `min(entities, 2^depth)`, so
 * at a SHALLOW operand path it saturates and the reduction is linear in the relevant-set size — but a model
 * deep enough that `2^depth` exceeds the relevant-set size has no such ceiling, and the pattern count grows
 * with the set. That is the region this measures: relevant sets whose DISTINCT wildcard patterns scale with
 * their size, which no timing guard covered ([`PartialSumOfProductsPerfTest`] grows the set but every entity
 * in it carries the same concrete shape, and it exercises the `SumOfProducts` gate, not this one).
 *
 * Kernel-free on purpose — this is an interpreter-internal cost, so there is nothing to compare against and no
 * reason to pay for a kernel JVM. CPU time on the measuring thread, per the algorithmic-work exception in
 * CLAUDE.md: the claim is about work performed, not user-visible latency.
 */
@Tag("perf")
class StarRelevanceReductionPerfTest {

    /**
     * The claim is that doubling the pattern count does not quadruple the work. A quadratic reduction breaches
     * that on every attempt while a contended slice inflates one ratio and does not reproduce, so the harness's
     * shared retry-on-breach path applies exactly: one estimate on the happy path, and a breach must survive the
     * median of independent re-measurements. This class hand-rolled that loop before it moved into the routine
     * tier (2026-08-13, IF295); the behaviour is the same and the machine-conditional enforcement is now the
     * shared one, so a hosted breach flags rather than fails like every other growth floor.
     */
    @Test
    fun theDominanceReductionStaysSubQuadraticInDistinctWildcardPatterns() {
        val relevantSets = listOf(PATTERNS, PATTERNS * 2).associateWith { relevantSet(it) }
        relevantSets.values.forEach { set -> repeat(WARMUP) { DmInterpreter(model).validatePart(document, set) } }

        val accepted = PerfHarness.assertSubQuadraticLane(
            label = "star-relevance dominance reduction",
            small = PATTERNS,
            large = PATTERNS * 2,
            identityAt = { patterns -> "antichain depth=$DEPTH wildcards=$WILDCARDS patterns=$patterns" },
            culprit = "the dominance scan comparing every candidate against every pattern (entities x patterns) " +
                "instead of short-circuiting on the antichain",
            floor = QUADRATIC_FLOOR,
        ) { patterns ->
            val relevant = relevantSets.getValue(patterns)
            PerfHarness.measureCurrentThreadCpu(warmup = 0, iters = REPEATS) {
                DmInterpreter(model).validatePart(document, relevant)
            }.medianMs
        }

        // A ratio over two unmeasurably small numbers passes whatever the reduction did. The antichain is built
        // so that one pass costs milliseconds, so a measurement rounding to zero means the clock answered.
        assertTrue(
            accepted.smallMs > 0.0,
            "the $PATTERNS-pattern measurement was ${accepted.smallMs}ms — too small to carry a ratio, so this " +
                "run asserted nothing about growth",
        )
    }

    /**
     * [patterns] entities on the operand path forming an ANTICHAIN — every mask wildcards exactly [WILDCARDS]
     * of the [DEPTH] repeatable levels, so no mask dominates another and none is removed. That matters because
     * the dominance scan short-circuits on its first hit: with masks that do dominate each other the average
     * candidate exits after a few probes and the cost looks linear however many patterns there are. The
     * antichain is the shape that actually forces `entities x patterns` comparisons, which is the bound under
     * test. `C(14,7) = 3432` masks are available, enough for both measured sizes.
     */
    private fun relevantSet(patterns: Int): Set<RelevantEntity> {
        val masks = (0 until (1 shl DEPTH)).filter { Integer.bitCount(it) == WILDCARDS }.take(patterns)
        check(masks.size == patterns) { "only ${masks.size} antichain masks available for $patterns" }
        return masks.mapTo(hashSetOf(RelevantEntity.anyRep("/Cart/Total"))) { mask ->
            RelevantEntity(
                FIELD,
                List(SEGMENTS) { level ->
                    when {
                        level == 0 || level == SEGMENTS - 1 -> RelevantEntity.ALL
                        (mask shr (level - 1)) and 1 == 1 -> RelevantEntity.ALL
                        else -> 1
                    }
                },
            )
        }
    }

    private companion object {
        const val DEPTH = 14
        const val WILDCARDS = 7
        const val SEGMENTS = DEPTH + 2          // root + DEPTH repeatable groups + the field
        const val PATTERNS = 1500
        const val WARMUP = 2
        const val REPEATS = 3

        /** Doubling the patterns doubles a linear reduction and QUADRUPLES a quadratic one, so the bar sits between. */
        const val QUADRATIC_FLOOR = 3.0
        const val FIELD = "/Cart/G1/G2/G3/G4/G5/G6/G7/G8/G9/G10/G11/G12/G13/G14/Qty"

        private val groups = (1..DEPTH).map { "/Cart/" + (1..it).joinToString("/") { i -> "G$i" } }

        val model = ModelDef(
            listOf(FieldDef(FIELD, Kind.NUMBER), FieldDef("/Cart/Total", Kind.NUMBER)),
            listOf(RuleDef("/Cart/SUM", "/Cart", "Sum(${starred()}) > 1", "SUM", "../Total")),
            repeatableGroups = groups.toSet(),
            declaredReps = groups.associateWith { 2 },
        )

        /** `G1*` then `G2*` … then `Qty` — the deepest legal all-rows aggregate operand for this model. */
        fun starred(): String = "/Cart/" + (1..DEPTH).joinToString("/") { "G$it*" } + "/Qty"

        val document = model.newDocument().also { doc ->
            doc.group("/Cart", 1)
            fun fill(level: Int, reps: List<Int>) {
                if (level > DEPTH) {
                    doc.field(FIELD, "10", *(reps + 1).toIntArray())
                    return
                }
                for (r in 1..2) {
                    doc.group(groups[level - 1], *(reps + r).toIntArray())
                    if (level < 3) fill(level + 1, reps + r) else fill(DEPTH + 1, reps + r + List(DEPTH - level) { 1 })
                }
            }
            fill(1, listOf(1))
        }
    }
}
