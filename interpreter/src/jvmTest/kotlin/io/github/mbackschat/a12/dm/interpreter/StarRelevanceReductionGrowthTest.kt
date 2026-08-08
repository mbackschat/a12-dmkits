package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

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
class StarRelevanceReductionGrowthTest {

    @Test
    fun theDominanceReductionStaysSubQuadraticInDistinctWildcardPatterns() {
        val small = measure(PATTERNS)
        val large = measure(PATTERNS * 2)
        val growth = large.toDouble() / small.coerceAtLeast(1)

        assertTrue(
            growth < 3.0,
            "doubling the number of distinct wildcard patterns at one operand path must not quadruple the " +
                "reduction's cost: ${PATTERNS} patterns took ${small / 1_000_000}ms, ${PATTERNS * 2} took " +
                "${large / 1_000_000}ms, growth ${"%.2f".format(growth)}x against a 3.0x bar " +
                "(quadratic would be 4.0x)",
        )
    }

    /** CPU nanos for one `validatePart` over a relevant set carrying [patterns] distinct wildcard shapes. */
    private fun measure(patterns: Int): Long {
        val relevant = relevantSet(patterns)
        repeat(WARMUP) { DmInterpreter(model).validatePart(document, relevant) }
        val bean = ManagementFactory.getThreadMXBean()
        val start = bean.currentThreadCpuTime
        repeat(REPEATS) { DmInterpreter(model).validatePart(document, relevant) }
        return (bean.currentThreadCpuTime - start) / REPEATS
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
