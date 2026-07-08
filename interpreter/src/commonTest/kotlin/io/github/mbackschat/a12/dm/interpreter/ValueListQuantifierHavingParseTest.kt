package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM+JS) PARSE lock for a `Having` filter on a value-list quantifier's operand (IG48). The
 * kernel grammar is `feldListeUndWerteListenBedingung: name '(' entityListe inOp wertListenSpezifier ')'`, with
 * `entityListe : entitySpec*` and each `entitySpec` carrying an optional `Mit` (=`Having`); the kernel accepts +
 * evaluates it on BOTH sides, so the parser must attach the filter to a starred operand on either side (the
 * eval semantics are locked by `adapter.laws.ValueListQuantifierHavingDiffTest`). Fixed by routing the VLQ
 * operands through `parseArg` (the same trailing-`Having` path aggregates/predicates use).
 */
class ValueListQuantifierHavingParseTest {

    private val countFilled = Cond.Pred("FieldFilled", listOf(Expr.Field("/Order/Items/Count")))

    @Test
    fun fieldsSideHavingParses() =
        assertEquals(
            Cond.ValueListQuant(
                ValueListQuantOp.AT_LEAST_ONE,
                listOf(Expr.Star("/Order/Items/Sku", countFilled)),
                listOf(Expr.StrLit("A"), Expr.StrLit("B")),
            ),
            parseCondition(
                """AtLeastOneFieldValueIncludedInValueList(Items*/Sku Having FieldFilled(Items/Count) In "A", "B")""",
                "/Order",
            ),
        )

    @Test
    fun valuesSideHavingParses() =
        assertEquals(
            Cond.ValueListQuant(
                ValueListQuantOp.NO,
                listOf(Expr.Field("/Order/ProductCode")),
                listOf(Expr.Star("/Order/Items/Sku", countFilled)),
            ),
            parseCondition(
                """NoFieldValueIncludedInValueList(ProductCode In Items*/Sku Having FieldFilled(Items/Count))""",
                "/Order",
            ),
        )
}
