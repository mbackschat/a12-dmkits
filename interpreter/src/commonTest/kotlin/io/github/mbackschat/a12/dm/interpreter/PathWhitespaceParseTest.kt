package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM+JS) lock: a path's `/` separator and `*` wildcard are WHITESPACE-INSENSITIVE, matching the
 * kernel's ANTLR lexer (whitespace on a hidden channel) — the gold standard. A hand-authored `Group* / Field`
 * (spaces around `/`, or before `*`) must parse to the SAME path AST as the space-free form, NOT be
 * mis-read as division / multiplication. Safe because a BARE identifier is never an arithmetic operand in the
 * grammar (`einfacheOperation` operands are bracketed `[…]`, constants, or named functions — `feldWertOp: '['
 * entitySpec ']'`), so a `/`/`*` after a bare path segment is unambiguously a separator/wildcard.
 */
class PathWhitespaceParseTest {

    // `Sum(<arg>) > 0` → the Sum function's first (starred) argument.
    private fun starArg(cond: String) =
        ((parseCondition(cond, "/Order") as Cond.Cmp).left as Expr.Func).args[0]

    @Test
    fun spacedSlashInStarredPathIsAPathNotDivision() =
        assertEquals(Expr.Star("/Order/Items/Count", bindDepth = 1), starArg("Sum(Items* / Count) > 0"))

    @Test
    fun adjacentAndSpacedAgree() =
        assertEquals(starArg("Sum(Items*/Count) > 0"), starArg("Sum( Items*  /  Count ) > 0"))

    @Test
    fun spaceBeforeWildcardIsAPathNotMultiplication() =
        assertEquals(Expr.Star("/Order/Items/Count", bindDepth = 1), starArg("Sum(Items * / Count) > 0"))

    @Test
    fun deepSpacedPathParses() =
        assertEquals(Expr.Field("/Order/Items/Detail/Note"),
            (parseCondition("FieldFilled(Items / Detail / Note)", "/Order") as Cond.Pred).args[0])

    @Test
    fun bracketedRefSpacedPathIsAPath() =
        assertEquals(Expr.Field("/Order/Items/Count"),
            (parseCondition("[Items / Count] > 0", "/Order") as Cond.Cmp).left)

    // Leading whitespace inside `[…]` before an absolute / parent-relative prefix (hidden in ANTLR).
    @Test
    fun bracketLeadingSpaceAbsolute() =
        assertEquals(Expr.Field("/Order/Quantity"),
            (parseCondition("[ /Order/Quantity] > 0", "/Order") as Cond.Cmp).left)

    @Test
    fun bracketLeadingSpaceParentRelative() =
        assertEquals(Expr.Field("/Quantity"),
            (parseCondition("FieldFilled([ ../Quantity])", "/Order") as Cond.Pred).args[0])

    /** Regression guard: genuine division of two BRACKETED value refs stays division (unaffected by the ws fix). */
    @Test
    fun bracketedDivisionStaysDivision() {
        val lhs = (parseCondition("[UnitPrice] / [Quantity] > 0", "/Order") as Cond.Cmp).left as Expr.Arith
        assertEquals(io.github.mbackschat.a12.dm.interpreter.ast.ArithOp.DIV, lhs.op)
    }
}
