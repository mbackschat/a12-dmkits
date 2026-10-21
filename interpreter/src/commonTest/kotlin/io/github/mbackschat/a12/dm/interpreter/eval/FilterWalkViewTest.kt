package io.github.mbackschat.a12.dm.interpreter.eval

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A [FilterWalkView] lists its grid in coordinate order and skips exactly what the sparse rule skips. The oracle is
 * the rule as `filteredRows` states it, run over every candidate: with a kept row pending, a candidate is visited
 * only when its coordinates cut after the first level differing from the kept row's are occupied. Random grids of
 * one to three free levels below a fixed scope, random stored rows and a random kept row; a fixed seed keeps a
 * failure reproducible.
 */
class FilterWalkViewTest {

    private val random = Random(20261021)
    private val scope = listOf(1, 2)

    @Test
    fun theGridIsListedInCoordinateOrder() {
        repeat(50) {
            val widths = IntArray(1 + random.nextInt(3)) { 1 + random.nextInt(4) }
            val view = view(widths)
            val expected = grid(widths)
            assertEquals(expected, view.indices.map(view::coordinatesAt))
        }
    }

    @Test
    fun aKeptRowSkipsExactlyTheCandidatesTheSparseRuleSkips() {
        repeat(300) {
            val widths = IntArray(1 + random.nextInt(3)) { 1 + random.nextInt(4) }
            val cells = grid(widths)
            val stored = cells.filter { random.nextInt(3) == 0 }
            val occupied = OccupiedPrefixes(buildSet { for (row in stored) for (depth in 1..row.size) add(row.take(depth)) })
            val view = view(widths)
            val kept = cells.random(random)
            // A search resumes after the kept row, never at or before it.
            for (from in cells.indexOf(kept) + 1..cells.size) {
                val expected = (from until cells.size).firstOrNull { j ->
                    val candidate = cells[j]
                    val changed = candidate.indices.firstOrNull { candidate[it] != kept[it] } ?: -1
                    changed < 0 || candidate.take(changed + 1) in occupied
                } ?: cells.size
                assertEquals(expected, view.nextUnskipped(from, kept, occupied), "widths ${widths.toList()} kept $kept from $from")
            }
        }
    }

    private fun view(widths: IntArray) = FilterWalkView(scope, widths, emptyMap()) { error("no row is read") }

    private fun grid(widths: IntArray): List<List<Int>> =
        widths.fold(listOf(scope)) { frontier, width -> frontier.flatMap { prefix -> (1..width).map { prefix + it } } }
}
