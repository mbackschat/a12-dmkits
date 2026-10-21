package io.github.mbackschat.a12.dm.interpreter.eval

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks [ContentTrie] to the full content scan it replaced. The oracle is that scan, kept here verbatim in
 * meaning: a row is evaluated iff some content shares its first `d` indices, `d` being where the content's path
 * leaves the group's (zero excluded, so another root never counts). The paths deliberately share and diverge at
 * every depth, include content placed on an ancestor group itself, and query groups the document never touches.
 */
class ContentTrieTest {

    @Test
    fun theGateAdmitsExactlyTheRowsTheFullScanDoes() {
        val random = Random(20261021)
        repeat(200) { round ->
            val content = List(random.nextInt(0, 12)) { randomPlacement(random) }
            val trie = ContentTrie<Pair<List<String>, List<Int>>> { it.second }
            content.forEach { placed -> trie.add(placed.first.joinToString("/", "/"), placed.first, placed) }
            for (group in GROUPS) {
                val gate = trie.gateFor(group)
                for (reps in rowsOf(group.size)) {
                    assertEquals(scanAdmits(content, group, reps), gate.admits(reps), "round $round $group $reps")
                }
            }
        }
    }

    @Test
    fun belowANodeIsEveryPlacementStrictlyUnderItsPath() {
        val random = Random(7)
        repeat(100) { round ->
            val content = List(random.nextInt(0, 12)) { randomPlacement(random) }
            val trie = ContentTrie<Pair<List<String>, List<Int>>> { it.second }
            content.forEach { placed -> trie.add(placed.first.joinToString("/", "/"), placed.first, placed) }
            for (group in GROUPS) {
                val below = ArrayList<Pair<List<String>, List<Int>>>()
                trie.forEachBelow(group) { below += it }
                val expected = content.filter { (parts, _) -> parts.size > group.size && parts.take(group.size) == group }
                assertEquals(expected.sortedBy { it.toString() }, below.sortedBy { it.toString() }, "round $round $group")
            }
        }
    }

    private fun scanAdmits(content: List<Pair<List<String>, List<Int>>>, group: List<String>, reps: List<Int>) =
        content.any { (parts, placed) ->
            val common = parts.zip(group).takeWhile { (a, b) -> a == b }.size
            common != 0 && placed.take(common) == reps.take(common)
        }

    private fun randomPlacement(random: Random): Pair<List<String>, List<Int>> {
        val parts = PATHS[random.nextInt(PATHS.size)]
        return parts to List(parts.size) { random.nextInt(1, 3) }
    }

    private fun rowsOf(depth: Int): List<List<Int>> =
        (1 until depth).fold(listOf(listOf(1), listOf(2))) { rows, _ -> rows.flatMap { listOf(it + 1, it + 2) } }

    private companion object {
        val PATHS = listOf(
            listOf("Shop"), listOf("Shop", "Orders"), listOf("Shop", "Orders", "Ref"),
            listOf("Shop", "Orders", "Lines"), listOf("Shop", "Orders", "Lines", "Qty"),
            listOf("Shop", "Orders", "Notes", "Text"), listOf("Shop", "Stock", "Bins", "Count"),
            listOf("Shop", "Name"), listOf("Archive", "Orders", "Ref"),
        )
        val GROUPS = listOf(
            listOf("Shop"), listOf("Shop", "Orders"), listOf("Shop", "Orders", "Lines"),
            listOf("Shop", "Orders", "Notes"), listOf("Shop", "Stock", "Bins"), listOf("Shop", "Returns"),
            listOf("Archive", "Orders"), listOf("Elsewhere", "Rows"),
        )
    }
}
