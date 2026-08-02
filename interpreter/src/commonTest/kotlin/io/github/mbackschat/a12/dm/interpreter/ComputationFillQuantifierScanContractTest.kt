package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.eval.CellState
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.PoisonedReadException
import io.github.mbackschat.a12.dm.interpreter.eval.PredicateOp
import io.github.mbackschat.a12.dm.interpreter.eval.ScanCell
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Portable SPEC20-12 lock for the exact computation fill-quantifier scan plans. Every expected trace records
 * the pass, its instantiated/declared range, and the consumed slot; an INVALID tail makes any over-read fail.
 */
class ComputationFillQuantifierScanContractTest {

    @Test
    fun allSevenOperatorsKeepTheirOrderedStopsAndCompositeRestarts() {
        assertScan(
            operator = PredicateOp.AT_LEAST_ONE_FIELD_FILLED,
            states = listOf(CellState.EMPTY, CellState.FILLED, CellState.INVALID),
            expectedResult = Tri.TRUE,
            expectedVisits = listOf(visit(1, Range.INSTANTIATED, 1), visit(1, Range.INSTANTIATED, 2)),
        )
        assertScan(
            operator = PredicateOp.NO_FIELD_FILLED,
            states = listOf(CellState.EMPTY, CellState.FILLED, CellState.INVALID),
            expectedResult = Tri.FALSE,
            expectedVisits = listOf(visit(1, Range.INSTANTIATED, 1), visit(1, Range.INSTANTIATED, 2)),
        )
        assertScan(
            operator = PredicateOp.ALL_FIELDS_FILLED,
            states = listOf(CellState.FILLED, CellState.EMPTY, CellState.INVALID),
            expectedResult = Tri.FALSE,
            expectedVisits = listOf(visit(1, Range.DECLARED, 1), visit(1, Range.DECLARED, 2)),
        )
        assertScan(
            operator = PredicateOp.NOT_ALL_FIELDS_FILLED,
            states = listOf(CellState.FILLED, CellState.EMPTY, CellState.INVALID),
            expectedResult = Tri.TRUE,
            expectedVisits = listOf(visit(1, Range.DECLARED, 1), visit(1, Range.DECLARED, 2)),
        )
        assertScan(
            operator = PredicateOp.MORE_THAN_ONE_FIELD_FILLED,
            states = listOf(CellState.FILLED, CellState.EMPTY, CellState.FILLED, CellState.INVALID),
            expectedResult = Tri.TRUE,
            expectedVisits = listOf(
                visit(1, Range.INSTANTIATED, 1),
                visit(1, Range.INSTANTIATED, 2),
                visit(1, Range.INSTANTIATED, 3),
            ),
        )
        assertScan(
            operator = PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED,
            states = listOf(CellState.EMPTY, CellState.FILLED, CellState.FILLED, CellState.INVALID),
            expectedResult = Tri.TRUE,
            expectedVisits = listOf(
                visit(1, Range.INSTANTIATED, 1),
                visit(1, Range.INSTANTIATED, 2),
                visit(2, Range.INSTANTIATED, 1),
                visit(2, Range.INSTANTIATED, 2),
                visit(2, Range.INSTANTIATED, 3),
            ),
        )
        assertScan(
            operator = PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED,
            states = listOf(CellState.EMPTY, CellState.FILLED, CellState.INVALID),
            expectedResult = Tri.TRUE,
            expectedVisits = listOf(
                visit(1, Range.INSTANTIATED, 1),
                visit(1, Range.INSTANTIATED, 2),
                visit(2, Range.DECLARED, 1),
            ),
        )
    }

    @Test
    fun notExactlyOneSecondStagePoisonsWhenItReachesAnInvalidTail() {
        val context = CountingContext(listOf(CellState.FILLED, CellState.EMPTY, CellState.INVALID))

        assertFailsWith<PoisonedReadException> {
            Interpreter(context, EvalConfig(computeMode = true))
                .eval(condition(PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED))
        }
        assertEquals(
            listOf(
                visit(1, Range.INSTANTIATED, 1),
                visit(2, Range.INSTANTIATED, 1),
                visit(2, Range.INSTANTIATED, 2),
                visit(2, Range.INSTANTIATED, 3),
            ),
            context.visits,
        )
    }

    private fun assertScan(
        operator: PredicateOp,
        states: List<CellState>,
        expectedResult: Tri,
        expectedVisits: List<Visit>,
    ) {
        val context = CountingContext(states)

        assertEquals(
            expectedResult,
            Interpreter(context, EvalConfig(computeMode = true))
                .eval(condition(operator)),
            operator.kernelName,
        )
        assertEquals(expectedVisits, context.visits, operator.kernelName)
        assertTrue(context.visits.size <= 2 * states.size, "${operator.kernelName} exceeded the two-pass bound")
    }

    private class CountingContext(private val states: List<CellState>) : EvalContext {
        val visits = mutableListOf<Visit>()
        private var scan = 0

        override val evaluated: Boolean = true
        override fun field(path: String): Val = Val.Unknown
        override fun filled(path: String): Boolean = false
        override fun star(path: String, bindDepth: Int): List<Val> = emptyList()
        override fun starOmission(path: String, bindDepth: Int): Boolean = false
        override fun displayValue(path: String): String? = null
        override fun declaredKind(path: String): Kind? = null

        override fun containedFieldCells(groupPath: String, declaredRange: Boolean): Sequence<ScanCell> = sequence {
            val currentScan = ++scan
            val range = if (declaredRange) Range.DECLARED else Range.INSTANTIATED
            states.forEachIndexed { slot, state ->
                visits += visit(currentScan, range, slot + 1)
                val raw = raw(state)
                yield(ScanCell("$groupPath/Slot${slot + 1}", listOf(1, 1, 1), raw, state, raw?.let(Val::Txt)))
            }
        }

        private fun raw(state: CellState): String? = if (state == CellState.EMPTY) null else state.name
    }

    private enum class Range { INSTANTIATED, DECLARED }

    private data class Visit(val scan: Int, val range: Range, val slot: Int)

    private companion object {
        const val ROOT = "/Policy"
        const val DETAILS = "$ROOT/Details"

        fun condition(operator: PredicateOp) = Cond.Pred(operator.kernelName, listOf(Expr.Field(DETAILS)))

        fun visit(scan: Int, range: Range, slot: Int) = Visit(scan, range, slot)
    }
}
