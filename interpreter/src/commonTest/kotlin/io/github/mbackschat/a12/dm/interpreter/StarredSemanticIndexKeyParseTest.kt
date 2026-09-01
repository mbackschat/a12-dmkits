package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A semantic-index KEY may carry a star, and `entitySuffix` reads only `path` and `outer` from it — the two
 * members [Expr.EntitySource] declares so a key site need not know which shape it got. A key site that
 * admits only [Expr.Field] escapes every classification a caller can make on the starred shape: a
 * `ClassCastException` is neither a parse refusal nor a result, and unlike a refusal it takes down a caller
 * that is not catching `Throwable`.
 *
 * Whether a starred key is semantically admissible is the kernel's question; parsing it is not.
 */
class StarredSemanticIndexKeyParseTest {

    @Test
    fun aStarredKeyKeepsItsPath() =
        assertEquals(
            "/Order/Lines/Detail/Sku",
            keyOf(parseCondition("FieldFilled(Sku For /Order*/Lines/Detail/Sku)", "/Order")),
        )

    @Test
    fun aStarredKeyOnAnAbsoluteSourceKeepsItsPath() =
        assertEquals(
            "/Order/Lines/Sku",
            keyOf(parseCondition("FieldFilled(/Order/Items/Sku For /Order*/Lines/Sku)", "/Order")),
        )

    /** An unstarred key reads through the same site — the declared type covers both shapes. */
    @Test
    fun anUnstarredKeyIsUnchanged() =
        assertEquals(
            "/Order/Lines/Sku",
            keyOf(parseCondition("FieldFilled(Sku For /Order/Lines/Sku)", "/Order")),
        )

    private fun keyOf(condition: Cond): String? =
        ((condition as Cond.Pred).args.single() as Expr.SemanticIndex).indexFieldPath
}
