package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `For <index>` semantic-index suffix (`Fuer`) rides the kernel's `entitySpec`, so it can follow ANY entity
 * reference — inside a `[...]` value ref AND as a BARE predicate/aggregate argument. The parser already handled
 * the bracketed `[field For key]` (locked by `SemanticIndexDiffTest`); this pins the previously-unparsed bare
 * forms real stored operations use: `FieldValueAsString(field For Index)` and semantic-index references inside
 * a `+` string concatenation. Both yield the same [Expr.SemanticIndex] the bracketed form does, so the
 * evaluation is already differential-locked. WI-P (SEMANTICS-FORMALIZATION-PROPOSAL, from Phase-0 S1; INTERPRETER-FINDINGS IF55).
 */
class ForIndexArgParseTest {

    @Test
    fun bareForIndexEqualsBracketedForm() =
        // A bare `field For key` operand parses to the same SemanticIndex as the bracketed `[field For key]`.
        assertEquals(
            parseExpression("[Product For MaxLine]", "/G"),
            parseExpression("Product For MaxLine", "/G"),
        )

    @Test
    fun forFieldIndexInsideFunctionArg() =
        assertEquals(
            Expr.Func("FieldValueAsString", listOf(Expr.SemanticIndex("/G/Quantity", indexFieldPath = "/G/MaxLine"))),
            parseExpression("FieldValueAsString(Quantity For MaxLine)", "/G"),
        )

    @Test
    fun forLiteralIndexInBareArg() =
        assertEquals(
            Expr.SemanticIndex("/G/Product", indexValue = "SKU-1"),
            parseExpression("""Product For "SKU-1"""", "/G"),
        )

    @Test
    fun forIndexInsideStringConcatenationParses() {
        // The exact real-corpus operation shape: a bracketed AND a function-arg semantic index in a `+` concat.
        val e = parseExpression(
            """ "P: " + [Product For MaxLine] + " x " + FieldValueAsString(Quantity For MaxLine) """.trim(), "/G")
        assertTrue(e is Expr.Arith, "a `+` string concatenation parses to an arithmetic chain")
    }
}
