package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthoredStringLiteralTest {
    @Test
    fun constantsAndSelectedKeysNormalizeExactlyOneCrlfPass() {
        for ((authored, decoded) in listOf(
            "A\rB" to "A\rB", "A\nB" to "A\nB", "A\r\nB" to "A\nB",
            "A\r\r\nB" to "A\r\nB", "A\r\n\r\nB" to "A\n\nB",
            "" to "", "\"\"" to "\"", "A\"\"B" to "A\"B", "A\\nB" to "A\\nB",
        )) {
            assertEquals(Expr.StrLit(decoded), parseExpression("\"$authored\"", "/Dispatch"))
            assertEquals(Expr.SemanticIndex("/Dispatch/Bins/Stock", indexValue = decoded),
                parseExpression("[Bins/Stock For \"$authored\"]", "/Dispatch"))
        }
    }

    @Test
    fun escapedQuoteDoesNotTerminateAnUnclosedLiteral() {
        for (authored in listOf("\"", "\"\"\"", "\"A\"\"B")) {
            assertFailsWith<IllegalArgumentException>(authored) { parseExpression(authored, "/Dispatch") }
        }
    }
}
