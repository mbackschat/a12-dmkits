package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class AggregateMemoScopeTest {

    @Test
    fun everyMemoizedAggregateSharesOneFoldPerScopeAndKeepsSiblingScopesSeparate() {
        val cases = listOf(
            FunctionOp.SUM to listOf(A),
            FunctionOp.MAX_VALUE to listOf(A),
            FunctionOp.MIN_VALUE to listOf(A),
            FunctionOp.NUMBER_OF_DIFFERENT_VALUES to listOf(A),
            FunctionOp.SUM_OF_PRODUCTS to listOf(A, B),
        )
        for ((op, paths) in cases) {
            val reads = ReadCount()
            val config = EvalConfig()
            val memo = HashMap<AggregateMemoKey, Val>()
            val expression = Expr.Func(op.kernelName, paths.map { Expr.Star(it, bindDepth = 1) })

            val first = Interpreter(context(prefix = listOf(1), row = 1, reads), config)
                .sharingAggregateMemo(memo).evalExpression(expression)
            val sameScope = Interpreter(context(prefix = listOf(1), row = 2, reads), config)
                .sharingAggregateMemo(memo).evalExpression(expression)
            val siblingScope = Interpreter(context(prefix = listOf(2), row = 1, reads), config)
                .sharingAggregateMemo(memo).evalExpression(expression)

            assertEquals(expected(op, listOf(2, 3), listOf(5, 7)), first, "$op first scope")
            assertEquals(first, sameScope, "$op same scope")
            assertEquals(expected(op, listOf(11, 13), listOf(17, 19)), siblingScope, "$op sibling scope")
            assertEquals(paths.size * 2, reads.value, "$op must fold once for each of two scopes")
        }
    }

    @Test
    fun computeModeNeverReusesAnAggregateAcrossMutableOverlayReads() {
        val reads = ReadCount()
        var values = listOf(num(2), num(3))
        val config = EvalConfig(computeMode = true)
        val memo = HashMap<AggregateMemoKey, Val>()
        val expression = Expr.Func(FunctionOp.SUM.kernelName, listOf(Expr.Star(A, bindDepth = 1)))
        val context = CountingContext(listOf(1), 1, reads) { values }

        assertEquals(num(5), Interpreter(context, config).sharingAggregateMemo(memo).evalExpression(expression))
        values = listOf(num(11), num(13))
        assertEquals(num(24), Interpreter(context, config).sharingAggregateMemo(memo).evalExpression(expression))
        assertEquals(2, reads.value)
    }

    @Test
    fun everyOperandScopeParticipatesInAMultiStarKey() {
        val reads = ReadCount()
        val memo = HashMap<AggregateMemoKey, Val>()
        val expression = Expr.Func(FunctionOp.SUM_OF_PRODUCTS.kernelName, listOf(
            Expr.Star(LEFT, bindDepth = 1),
            Expr.Star(RIGHT, bindDepth = 1),
        ))
        val leftScope = CountingContext(
            prefix = emptyList(),
            row = 1,
            reads = reads,
            values = { path -> if (path == LEFT) listOf(num(2)) else listOf(num(3)) },
            scopePrefixes = mapOf("/Root/LeftRows" to listOf(1), "/Root/RightRows" to listOf(1)),
        )
        val differentRightScope = CountingContext(
            prefix = emptyList(),
            row = 1,
            reads = reads,
            values = { path -> if (path == LEFT) listOf(num(2)) else listOf(num(5)) },
            scopePrefixes = mapOf("/Root/LeftRows" to listOf(1), "/Root/RightRows" to listOf(2)),
        )

        assertEquals(num(6), Interpreter(leftScope).sharingAggregateMemo(memo).evalExpression(expression))
        assertEquals(num(10), Interpreter(differentRightScope).sharingAggregateMemo(memo).evalExpression(expression))
        assertEquals(4, reads.value)
    }

    private fun context(prefix: List<Int>, row: Int, reads: ReadCount): CountingContext {
        val a = if (prefix == listOf(1)) listOf(num(2), num(3)) else listOf(num(11), num(13))
        val b = if (prefix == listOf(1)) listOf(num(5), num(7)) else listOf(num(17), num(19))
        return CountingContext(prefix, row, reads) { path -> if (path == A) a else b }
    }

    private fun expected(op: FunctionOp, a: List<Int>, b: List<Int>): Val = when (op) {
        FunctionOp.SUM -> num(a.sum())
        FunctionOp.MAX_VALUE -> num(a.max())
        FunctionOp.MIN_VALUE -> num(a.min())
        FunctionOp.NUMBER_OF_DIFFERENT_VALUES -> num(a.distinct().size)
        FunctionOp.SUM_OF_PRODUCTS -> num(a.zip(b).sumOf { (left, right) -> left * right })
        else -> error("not a memoized aggregate: $op")
    }

    private fun num(value: Int): Val.Num = Val.Num(dec(value.toString()))

    private class ReadCount(var value: Int = 0)

    private class CountingContext(
        private val prefix: List<Int>,
        private val row: Int,
        private val reads: ReadCount,
        private val scopePrefixes: Map<String, List<Int>> = emptyMap(),
        private val values: (String) -> List<Val>,
    ) : EvalContext {
        override val evaluated: Boolean = true
        override fun field(path: String): Val = error("not a scalar test")
        override fun filled(path: String): Boolean = false
        override fun star(path: String, bindDepth: Int): List<Val> = values(path).also { reads.value++ }
        override fun starAll(path: String, bindDepth: Int): List<Val> = star(path, bindDepth)
        override fun starScanStates(path: String, bindDepth: Int): List<CellState> =
            values(path).map { CellState.FILLED }
        override fun starOmission(path: String, bindDepth: Int): Boolean = false
        override fun displayValue(path: String): String? = null
        override fun declaredKind(path: String): Kind = Kind.NUMBER
        override fun repsOf(path: String): List<Int> = prefix + row + 1
        override fun scopePrefixOf(groupPath: String, bindDepth: Int): List<Int> = scopePrefixes[groupPath] ?: prefix
    }

    private companion object {
        const val A = "/Root/Rows/A"
        const val B = "/Root/Rows/B"
        const val LEFT = "/Root/LeftRows/A"
        const val RIGHT = "/Root/RightRows/B"
    }
}
