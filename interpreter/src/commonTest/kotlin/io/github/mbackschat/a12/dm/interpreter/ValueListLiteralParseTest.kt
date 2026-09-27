package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * The scalar value-list predicates `FieldValueIncludedInValueList` / `FieldValueNotIncludedInValueList` — the
 * kernel's `feldUndWerteListenBedingung` `'(' entitySpec ',' wertListenSpezifier2 ')'`, i.e. a COMMA-separated
 * literal value list (`(field, "a", "b", …)`), which is exactly how the kernel stores them. This maps onto the
 * existing generic-predicate AST ([Cond.Pred] with the field then the value members as args) that the
 * evaluator's `valueInList` already interprets (locked by `ValueListDiffTest`) — this pins the PARSE of the
 * literal form. WI-P (SEMANTICS-FORMALIZATION-PROPOSAL, from Phase-0 S1; INTERPRETER-FINDINGS IF55). String members
 * isolate this grammar arm; numeric members are also safe in whole-AST equality because `Dec` has value semantics since IF41.
 */
class ValueListLiteralParseTest {

    @Test
    fun includedInValueListLiteralForm() =
        assertEquals(
            Cond.Pred("FieldValueIncludedInValueList",
                listOf(Expr.Field("/G/Product"), Expr.StrLit("Smartphone"), Expr.StrLit("Tablet"))),
            parseCondition("""FieldValueIncludedInValueList(Product, "Smartphone", "Tablet")""", "/G"),
        )

    @Test
    fun notIncludedInValueListLiteralForm() =
        assertEquals(
            Cond.Pred("FieldValueNotIncludedInValueList",
                listOf(Expr.Field("/G/Status"), Expr.StrLit("cancelled"))),
            parseCondition("""FieldValueNotIncludedInValueList(Status, "cancelled")""", "/G"),
        )
    @Test
    fun numericTokensAllowWhitespaceButNotCommentsInsideTheLiteral() {
        val bodies = listOf("[Amount] > %s", "FieldValueIncludedInValueList(Amount, %s)", "[Amount] > {%s + [Tax]}")
        for (literal in listOf("-2.50", "2.50", "-0.00", "0.00")) {
            val sites = if (literal.startsWith('-')) listOf(1, 2, 3) else listOf(1, 2)
            for (body in bodies) {
                val plain = body.replace("%s", literal)
                for (at in sites) {
                    for (gap in listOf(" ", "\t", "\r", "\n", "\u000C")) {
                        val separated = literal.take(at) + gap + literal.drop(at)
                        assertEquals(parseCondition(plain, "/Order"),
                            parseCondition(body.replace("%s", separated), "/Order"))
                    }
                    val comment = literal.take(at) + ";; note\n" + literal.drop(at)
                    assertFails { parseCondition(body.replace("%s", comment), "/Order") }
                }
            }
        }
    }

}
