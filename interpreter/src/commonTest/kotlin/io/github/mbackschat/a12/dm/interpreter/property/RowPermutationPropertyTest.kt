package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.model.Message
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Property 3 — Row-permutation equivariance** (proposal §8): permuting the repetition rows of a group permutes
 * the fired pointers but leaves the outcome otherwise identical — the **code multiset is invariant**, and every
 * firing is the same one, just relabeled to the new row. Verified by re-labeling one repeatable level's row
 * indices (single-level `/Cart/Lines`, and the top level of nested `/M/G1`, carrying its subtree) and comparing:
 * the `code|type` multiset, and the full-signature multiset with the permuted level's index **blanked** (so a
 * pure relabel matches, but any added / dropped / re-targeted firing does not). The models' rules are all
 * order-insensitive (per-row comparisons, `Sum` aggregates, index uniqueness) — no `FirstFilledValue`. Sweeps
 * both models × [PropertySupport.SEEDS] on JVM + JS.
 */
class RowPermutationPropertyTest {

    /** A repeatable group to permute: its [group] path, the 0-based rep [level] of its own row index, and the
     *  path [segment] name (for blanking that level in a rendered pointer). */
    private class Case(val scenario: PropertySupport.Scenario, val group: String, val level: Int, val segment: String)

    private val cases = listOf(
        Case(PropertySupport.singleRep, "/Cart/Lines", 1, "Lines"),
        Case(PropertySupport.nested, "/M/G1", 1, "G1"),
    )

    @Test
    fun permutingRowsRelabelsPointersAndKeepsTheCodeMultiset() {
        var firings = 0
        for (c in cases) {
            val engine = DmInterpreter(c.scenario.model)
            for (seed in PropertySupport.SEEDS) {
                val placements = c.scenario.placements(seed)
                val n = rowCount(placements, c.group, c.level)
                if (n < 2) continue
                val perm = permutation(n, Random(seed + 991))
                val original = engine.validate(PropertySupport.documentOf(c.scenario.model, placements)).messages
                val permuted = engine.validate(
                    PropertySupport.documentOf(c.scenario.model, relabel(placements, c.group, c.level, perm))).messages
                firings += original.size
                val where = "${c.scenario.name} seed=$seed perm=$perm"
                assertEquals(
                    original.map { "${it.code}|${it.type}" }.sorted(), permuted.map { "${it.code}|${it.type}" }.sorted(),
                    "$where: the code/type multiset changed under a row permutation")
                assertEquals(
                    original.map { blank(it, c.segment) }.sorted(), permuted.map { blank(it, c.segment) }.sorted(),
                    "$where: a firing was added / dropped / re-targeted beyond a pure row relabel")
            }
        }
        assertTrue(firings > 0, "the permutation sweep never produced a firing — the sample is vacuous")
    }

    private fun rowCount(placements: List<Placement>, group: String, level: Int): Int =
        placements.filter { it.path == group && level < it.reps.size }.map { it.reps[level] }.distinct().size

    private fun permutation(n: Int, rnd: Random): Map<Int, Int> {
        val shuffled = (1..n).shuffled(rnd)
        return (1..n).associateWith { shuffled[it - 1] }
    }

    /** Relabel the [level] rep of every placement addressed at or under [group] via [perm]. */
    private fun relabel(placements: List<Placement>, group: String, level: Int, perm: Map<Int, Int>): List<Placement> =
        placements.map { p ->
            if ((p.path == group || p.path.startsWith("$group/")) && level < p.reps.size)
                Placement(p.kind, p.path, p.reps.toMutableList().also { it[level] = perm.getValue(it[level]) }, p.value)
            else p
        }

    /** A message signature with the [seg] segment's index blanked: `/Cart[1]/Lines[3]/Qty` → `…/Lines[*]/…`. */
    private fun blank(m: Message, seg: String): String =
        "${m.code}|${m.type}|" + m.errorPath.replace(Regex("/$seg\\[\\d+\\]"), "/$seg[*]")
}
