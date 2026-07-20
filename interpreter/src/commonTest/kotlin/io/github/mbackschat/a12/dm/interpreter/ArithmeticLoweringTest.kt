package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import kotlin.test.Test
import kotlin.test.assertEquals

/** The model-static arithmetic lowering is one ordered post-order pass, never a global/fixed-point fraction rewrite. */
class ArithmeticLoweringTest {

    @Test
    fun divisionRootIsPulledAfterOrdinaryFactorsRegardlessOfAuthoredSide() {
        val expected = div(mul(field("A"), field("B")), field("C"))
        assertEquals(expected, parseExpression("[A] * { [B] / [C] }", "/G"))
        assertEquals(expected, parseExpression("{ [B] / [C] } * [A]", "/G"))
    }

    @Test
    fun generatedProductsAreNotVisitedAgain() {
        val expected = div(mul(field("X"), div(field("A"), field("B"))), field("C"))
        val fixedPoint = div(div(mul(field("X"), field("A")), field("B")), field("C"))
        val actual = parseExpression("[X] * { { [A] / [B] } / [C] }", "/G")

        assertEquals(expected, actual)
        kotlin.test.assertNotEquals(fixedPoint, actual)
    }

    private fun field(name: String) = Expr.Field("/G/$name")
    private fun mul(left: Expr, right: Expr) = Expr.Arith(ArithOp.MUL, left, right)
    private fun div(left: Expr, right: Expr) = Expr.Arith(ArithOp.DIV, left, right)
}
