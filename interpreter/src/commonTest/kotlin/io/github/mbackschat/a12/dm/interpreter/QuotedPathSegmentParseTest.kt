package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform lock for the grammar's single-quoted path segments. A keyword-shaped entity name remains a
 * path segment; the quotes are syntax and do not enter the resolved name path.
 */
class QuotedPathSegmentParseTest {

    @Test
    fun absoluteQuotedSegmentResolvesToItsEntityName() =
        assertEquals(
            Expr.Field("/Length/Value"),
            (parseCondition("FieldFilled(/'Length'/Value)", "/Policy") as Cond.Pred).args.single(),
        )

    @Test
    fun relativeQuotedSegmentResolvesAgainstTheDeclaringGroup() =
        assertEquals(
            Expr.Field("/Policy/Length/Value"),
            (parseCondition("FieldFilled('Length'/Value)", "/Policy") as Cond.Pred).args.single(),
        )
}
