package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * A [FilterWalkView] reads a stored row at its own coordinates and builds a missing one everywhere else. The oracle
 * is the grid itself: random grids below a fixed scope, a random stored subset, and every index read back. The
 * view's search assumes its stored rows in coordinate order, so a caller that hands them otherwise is refused
 * rather than answered from the wrong row.
 */
class FilterWalkStoredRowsTest {

    private val random = Random(20261021)
    private val scope = listOf(1, 2)

    @Test
    fun aStoredRowIsReadAtItsCoordinatesAndAMissingOneIsBuiltElsewhere() {
        repeat(200) {
            val widths = IntArray(1 + random.nextInt(3)) { 1 + random.nextInt(4) }
            val cells = grid(widths)
            val stored = cells.filter { random.nextInt(3) == 0 }.associateWith { Row(it) }
            val view = FilterWalkView(scope, widths, stored.map { (reps, row) -> reps to row }) { Row(it, missing = true) }
            cells.forEachIndexed { index, reps ->
                val read = view[index] as Row
                assertEquals(reps, read.reps, "widths ${widths.toList()} index $index")
                stored[reps]?.let { assertSame(it, read) } ?: assertEquals(true, read.missing, "built at $reps")
            }
        }
    }

    @Test
    fun storedRowsOutOfCoordinateOrderAreRefused() {
        val widths = intArrayOf(3)
        val rows = listOf(listOf(1, 2, 3), listOf(1, 2, 1)).map { it to Row(it) }
        assertFailsWith<IllegalArgumentException> { FilterWalkView(scope, widths, rows) { Row(it, missing = true) } }
        assertFailsWith<IllegalArgumentException> {
            FilterWalkView(scope, widths, listOf(rows[1], rows[1])) { Row(it, missing = true) }
        }
    }

    private fun grid(widths: IntArray): List<List<Int>> =
        widths.fold(listOf(scope)) { frontier, width -> frontier.flatMap { prefix -> (1..width).map { prefix + it } } }

    private class Row(val reps: List<Int>, val missing: Boolean = false) : EvalContext {
        override val evaluated: Boolean = true
        override fun field(path: String): Val = Val.Unknown
        override fun filled(path: String): Boolean = false
        override fun star(path: String, bindDepth: Int): List<Val> = emptyList()
        override fun starOmission(path: String, bindDepth: Int): Boolean = false
        override fun displayValue(path: String): String? = null
        override fun declaredKind(path: String): Kind? = null
    }
}
