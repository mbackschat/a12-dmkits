package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposedDependencyTest {
    @Test
    fun aComposedOperandRetainsSourceFilterAndKeyDependencies() {
        val payload = "/Order/Packages/Rows/Quantity"
        val key = "/Order/Wanted"
        val filterTarget = "/Order/Filters/Flag"
        val filter = Cond.Pred("FieldFilled", listOf(Expr.SemanticIndex(filterTarget, indexValue = "active")))
        for (source in listOf(Expr.Field(payload), Expr.Star(payload, having = filter, bindDepth = 2))) {
            for (index in listOf(null, Expr.EntityIndex.Literal("P1"), Expr.EntityIndex.Field(key))) {
                for (category in listOf(null, "Band")) {
                    val operand = Expr.EntitySpec(source, category, index)
                    val filterPaths = if (source is Expr.Star) listOf(filterTarget) else emptyList()
                    val keyPaths = if (index is Expr.EntityIndex.Field) listOf(key) else emptyList()
                    assertEquals(listOf(payload) + filterPaths + keyPaths, referencedFieldPaths(operand))
                    assertEquals(filterPaths + if (index != null) listOf(payload) else emptyList(), semanticIndexTargets(operand))
                }
            }
        }
        assertEquals(emptyList(), referencedFieldPaths(Expr.Func("CurrentRepetition", listOf(Expr.Field("/Order/Packages")))))
    }
}
