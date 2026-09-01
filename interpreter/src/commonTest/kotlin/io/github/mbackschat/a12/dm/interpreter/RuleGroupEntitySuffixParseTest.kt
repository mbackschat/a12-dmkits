package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `RuleGroup` (DE `RegelKontext`) is the rule's own group, and the owner grammar lets an `entitySpec` suffix
 * ride it like any other entity reference — `GroupFilled(RuleGroup For "a")` selects that group by semantic
 * index. The bare-argument branch reaches the keyword without going through `readRef`, so it has to apply the
 * suffix itself; the `[...]`-value and path-argument branches get it from their shared call site.
 */
class RuleGroupEntitySuffixParseTest {

    @Test
    fun aLiteralSemanticIndexRidesTheSelfReference() =
        assertEquals(
            "a",
            indexOf(parseCondition("""GroupFilled(RuleGroup For "a")""", "/Order")),
        )

    /** The index selects the rule's own group, not a child named after the keyword. */
    @Test
    fun theIndexedSelfReferenceKeepsTheRulesGroupPath() =
        assertEquals(
            "/Order",
            pathOf(parseCondition("""GroupFilled(RuleGroup For "a")""", "/Order")),
        )

    /** A suffix is optional: a bare self-reference reads through the same branch as a plain field. */
    @Test
    fun aBareSelfReferenceStaysAPlainField() =
        assertEquals(
            "/Order",
            ((parseCondition("GroupFilled(RuleGroup)", "/Order") as Cond.Pred).args.single() as Expr.Field).path,
        )

    private fun indexed(condition: Cond): Expr.SemanticIndex =
        (condition as Cond.Pred).args.single() as Expr.SemanticIndex

    private fun indexOf(condition: Cond): String? = indexed(condition).indexValue

    private fun pathOf(condition: Cond): String = indexed(condition).path
}
