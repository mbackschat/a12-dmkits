package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM+JS) lock for IF91 (IG49) — whitespace WITHIN a `..` up-run is insignificant, matching the
 * kernel's ANTLR lexer (hidden channel), the sibling of IF75's `/`/`*` whitespace-insensitivity. Two positions:
 * a space before the descent (`.. /seg` ≡ `../seg`) and a space before the turning-point label (`.. Turn`
 * ≡ `..Turn`, the label discarded). Both must parse to the SAME AST as the space-free form, and the adjacent
 * forms must be unaffected.
 */
class ParentUpWhitespaceParseTest {

    // `FieldFilled(<ref>)` → the predicate's first (path) argument, paths relative to /Order/Items.
    private fun ref(cond: String, base: String = "/Order/Items") =
        (parseCondition("FieldFilled($cond)", base) as Cond.Pred).args[0]

    // ── a space before the descent `/`: `.. /seg` ≡ `../seg` (walk up one, descend into seg) ──
    @Test fun spacedDescentEqualsAdjacent() =
        assertEquals(Expr.Field("/Order/Detail"), ref(".. /Detail"))

    @Test fun spacedDescentEqualsAdjacentForm() =
        assertEquals(ref("../Detail"), ref(".. /Detail"))

    // ── a space before the turning-point label: `.. Turn` ≡ `..Turn` (the ancestor group; label discarded) ──
    @Test fun spacedLabelEqualsAdjacent() =
        assertEquals(Expr.Field("/Order"), ref(".. Turn"))

    @Test fun spacedLabelEqualsAdjacentForm() =
        assertEquals(ref("..Turn"), ref(".. Turn"))

    // A bare `..` with trailing space then the arg list end is still the ancestor group.
    @Test fun spacedBareUpIsAncestor() =
        assertEquals(Expr.Field("/Order"), ref(".. "))

    // ── inside a `[…]` value ref (IF75 handled the leading bracket space; this is the up-run interior) ──
    @Test fun spacedDescentInBracketRef() =
        assertEquals(Expr.Field("/Order/Quantity"),
            (parseCondition("[.. /Quantity] > 0", "/Order/Items") as Cond.Cmp).left)

    // ── regression: the adjacent forms are unchanged, incl. a multi-level up-run ──
    @Test fun adjacentDescentUnchanged() =
        assertEquals(Expr.Field("/Order/Detail"), ref("../Detail"))

    @Test fun adjacentMultiLevelUnchanged() =
        assertEquals(Expr.Field("/Order/X"), ref("../../X", base = "/Order/Items/Detail"))

    @Test fun adjacentBareLabelUnchanged() =
        assertEquals(Expr.Field("/Order"), ref("..Turn"))
}
