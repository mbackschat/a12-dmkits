package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scalar value-list predicates `FieldValueIncludedInValueList` / `FieldValueNotIncludedInValueList` — the
 * kernel's `feldUndWerteListenBedingung` `'(' entitySpec ',' wertListenSpezifier2 ')'`, i.e. a COMMA-separated
 * literal value list (`(field, "a", "b", …)`), which is exactly how the kernel stores them. This maps onto the
 * existing generic-predicate AST ([Cond.Pred] with the field then the value members as args) that the
 * evaluator's `valueInList` already interprets (locked by `ValueListDiffTest`) — this pins the PARSE of the
 * literal form. WI-P (SEMANTICS-FORMALIZATION-PROPOSAL, from Phase-0 S1; INTERPRETER-FINDINGS IF55). String members only —
 * a NUMBER literal's `Dec` compares by identity (IG4), which whole-AST `assertEquals` can't compare.
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
}
