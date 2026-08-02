package io.github.mbackschat.a12.dm.interpreter.parse

import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr

/**
 * The kernel's model-static arithmetic transform: one ordered post-order pass over the authored tree.
 *
 * Each original multiplication pulls only immediate division-root operands after its children have
 * been visited. Ordinary factors remain first and ordered; extracted numerators and denominators
 * follow in division encounter order. The newly built products are deliberately not visited again.
 */
internal fun Cond.lowerArithmetic(): Cond = when (this) {
    is Cond.Cmp -> copy(left = left.lowerArithmetic(), right = right.lowerArithmetic())
    is Cond.And -> copy(left = left.lowerArithmetic(), right = right.lowerArithmetic())
    is Cond.Or -> copy(left = left.lowerArithmetic(), right = right.lowerArithmetic())
    is Cond.Not -> copy(c = c.lowerArithmetic())
    is Cond.Pred -> copy(args = args.map(Expr::lowerArithmetic))
    is Cond.Pattern -> copy(value = value.lowerArithmetic(), pattern = pattern.lowerArithmetic())
    is Cond.Tolerance -> copy(left = left.lowerArithmetic(), right = right.lowerArithmetic())
    is Cond.ValueListQuant -> copy(
        fields = fields.map(Expr::lowerArithmetic),
        values = values.map(Expr::lowerArithmetic),
    )
    is Cond.Custom -> this
}

internal fun Expr.lowerArithmetic(): Expr = when (this) {
    is Expr.Field, is Expr.NumLit, is Expr.StrLit, is Expr.BoolLit, is Expr.Const,
    is Expr.SemanticIndex, is Expr.EnumCategory -> this
    is Expr.Star -> copy(having = having?.lowerArithmetic())
    is Expr.EntitySpec -> copy(source = when (val entity = source) {
        is Expr.Field -> entity
        is Expr.Star -> entity.copy(having = entity.having?.lowerArithmetic())
    })
    is Expr.Func -> copy(args = args.map(Expr::lowerArithmetic))
    is Expr.Arith -> {
        val loweredLeft = left.lowerArithmetic()
        val loweredRight = right.lowerArithmetic()
        if (op == ArithOp.MUL) {
            pullImmediateDivisions(loweredLeft, loweredRight)
        } else {
            copy(left = loweredLeft, right = loweredRight)
        }
    }
}

private fun pullImmediateDivisions(left: Expr, right: Expr): Expr {
    val operands = listOf(left, right)
    val divisions = operands.filterIsInstance<Expr.Arith>().filter { it.op == ArithOp.DIV }
    if (divisions.isEmpty()) return Expr.Arith(ArithOp.MUL, left, right)

    val ordinary = operands.filterNot { it is Expr.Arith && it.op == ArithOp.DIV }
    val numerator = leftFoldProduct(ordinary + divisions.map(Expr.Arith::left))
    val denominator = leftFoldProduct(divisions.map(Expr.Arith::right))
    return Expr.Arith(ArithOp.DIV, numerator, denominator)
}

private fun leftFoldProduct(factors: List<Expr>): Expr =
    factors.reduce { left, right -> Expr.Arith(ArithOp.MUL, left, right) }
