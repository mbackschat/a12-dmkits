package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.eval.CellState
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.ScanCell
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/** Deterministic JVM/Node work bound for RV04-4: a first-cell witness performs one scan event at N and 4N. */
class BareGroupScanLazinessTest {

    @Test
    fun firstWitnessSkipsEveryTailLookupAtBothScales() {
        assertEquals(1, readsFor(2_000))
        assertEquals(1, readsFor(8_000))
    }

    private fun readsFor(size: Int): Int {
        val context = CountingContext(size)
        val result = Interpreter(context, EvalConfig(computeMode = true))
            .eval(parseCondition("AtLeastOneFieldFilled(Details)", ROOT))
        assertEquals(Tri.TRUE, result)
        return context.reads
    }

    private class CountingContext(private val size: Int) : EvalContext {
        var reads = 0
            private set

        override val evaluated: Boolean = true
        override fun field(path: String): Val = Val.Unknown
        override fun filled(path: String): Boolean = false
        override fun star(path: String, bindDepth: Int): List<Val> = emptyList()
        override fun starOmission(path: String, bindDepth: Int): Boolean = false
        override fun displayValue(path: String): String? = null
        override fun declaredKind(path: String): Kind? = null

        override fun containedFieldCells(groupPath: String, declaredRange: Boolean): Sequence<ScanCell> = sequence {
            for (i in 0 until size) {
                reads++
                if (i == 0) {
                    yield(ScanCell("$groupPath/First", listOf(1, 1, 1), "x", CellState.FILLED, Val.Txt("x")))
                } else {
                    yield(ScanCell("$groupPath/Tail$i", listOf(1, 1, 1), null, CellState.EMPTY, null))
                }
            }
        }
    }

    private companion object {
        const val ROOT = "/Policy"
    }
}
