package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ValueKind
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.rulekit.dsl.Arithmetic
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.Concatenation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumCategoryAccess
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral

/**
 * The leaf and operator shapes of a value expression — literals, the no-operand constants, arithmetic — as
 * opposed to the named functions [Functions] maps.
 *
 * Reached for anything in a value slot that is not a function call, so a gap here blocks an operand wherever
 * it appears rather than in one operator.
 */
internal class Expressions(private val mapping: Mapping) {

    fun map(node: Expr): Calculation = when (node) {
        is Expr.NumLit -> NumberLiteral(node.v.v)
        is Expr.StrLit -> StringLiteral(node.v)
        is Expr.BoolLit -> BoolLiteral(node.v)
        is Expr.Field -> mapping.fieldRef(node).value()
        is Expr.SemanticIndex -> mapping.indexedValue(node)
        is Expr.Const -> constant(node)
        is Expr.Arith -> arithmetic(node)
        is Expr.EnumCategory -> enumCategory(node)
        is Expr.Star -> throw NoTypedOperationException(
            "a star in a scalar value slot: rulekit types the slot to a single field value, which is how it" +
                " states MVK_NO_WILDCARDS_ALLOWED"
        )
        else -> throw UnmappedConstructException("expression node " + node.javaClass.simpleName)
    }

    /**
     * `+` is overloaded: string CONCATENATION when either operand is textual, numeric addition otherwise,
     * decided by the operands rather than by the symbol. rulekit splits the two across [Concatenation] and
     * [Arithmetic], whose `kind()` is NUMBER by construction, so building the arithmetic node for a textual
     * `+` makes the whole expression claim to be a number and the comparison around it then asks a string
     * field for a numeric handle.
     *
     * Decided here rather than in the operator that consumes the result: an `Arith` node can sit in any value
     * slot, so there is no one consumer to special-case. That is the opposite of a bare quoted literal, whose
     * kind depends on its SIBLINGS and so has to be settled by the call it appears in.
     */
    private fun arithmetic(arith: Expr.Arith): Calculation {
        val left = mapping.calculation(arith.left)
        val right = mapping.calculation(arith.right)
        if (arith.op == ArithOp.ADD && (isTextual(left) || isTextual(right))) {
            return Concatenation(listOf(left, right))
        }
        return Arithmetic(arithOp(arith.op), left, right)
    }

    /**
     * A no-operand constant. `FirstDay`/`LastDay` are day-specification markers the parser emits as the same
     * node shape (Parser.kt's `CONSTANTS` set) but never evaluates standalone — they are consumed structurally
     * by `ValueAsDate`, which this bridge does not yet map — so reaching one here is a gap, not a value to
     * resolve.
     */
    private fun constant(node: Expr.Const): Calculation = when (node.name) {
        "Today" -> Dsl.today().expression()
        "Now" -> Dsl.now().expression()
        "BaseYear" -> Dsl.baseYear().expression()
        else -> throw UnmappedConstructException("constant " + node.name)
    }

    /**
     * `[field -> Category]` — the enum-category navigation, an [EnumCategoryAccess]. Which categories a field
     * declares is model knowledge [EnumCategoryAccess] reads for itself, so an undeclared name is rulekit's
     * refusal rather than a gate here.
     */
    private fun enumCategory(node: Expr.EnumCategory): Calculation {
        val field = mapping.fieldRef(Expr.Field(node.path, node.outer))
        return EnumCategoryAccess(field, node.category, node.outer)
    }

    private companion object {
        /** Whether an operand carries text, in the sense the kernel's `producesString` means it. */
        private fun isTextual(operand: Calculation): Boolean = when (operand.kind()) {
            ValueKind.STRING, ValueKind.CUSTOM -> true
            else -> false
        }

        private fun arithOp(op: ArithOp): Arithmetic.Op = when (op) {
            ArithOp.ADD -> Arithmetic.Op.ADD
            ArithOp.SUB -> Arithmetic.Op.SUBTRACT
            ArithOp.MUL -> Arithmetic.Op.MULTIPLY
            ArithOp.DIV -> Arithmetic.Op.DIVIDE
            ArithOp.POW -> Arithmetic.Op.POWER
        }
    }
}
