package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.eval.Aggregates
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.FunctionOp
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM + JS locks for numeric value-aggregate encounter order and declaration-aware fill polarity. */
class NumericAggregateSemanticsTest {

    @Test fun sumRoundsAfterEveryAdditionAndPreservesEncounterOrder() {
        val magnitude = "1" + "0".repeat(50)
        val negativeMagnitude = "-$magnitude"

        assertEquals(
            Val.Num(dec("0.6")),
            Aggregates.sum(listOf(num(magnitude), num(negativeMagnitude), num("0.6"))),
            "cancellation before the fractional value retains 0.6",
        )
        assertEquals(
            Val.Num(dec("1")),
            Aggregates.sum(listOf(num(negativeMagnitude), num("0.6"), num(magnitude))),
            "moving the fractional value across the cancellation changes the staged precision result",
        )
        assertEquals(
            Val.Num(dec(magnitude)),
            Aggregates.sum(listOf(num("9".repeat(50)), num("0.6"))),
            "each addition rounds immediately at precision 50 with HALF_UP",
        )
    }

    @Test fun allEmptyUnsignedNumericAggregatesAreFillableBothWays() {
        val model = ModelDef(
            fields = listOf(FieldDef(ANCHOR, Kind.STRING), FieldDef(UNSIGNED_LINE, Kind.NUMBER, signed = false)),
            rules = emptyList(),
            repeatableGroups = setOf(LINES),
            declaredReps = mapOf(LINES to 2),
        )
        val doc = model.newDocument().field(ANCHOR, "present", 1, 1)
        for (op in listOf(FunctionOp.SUM, FunctionOp.MAX_VALUE, FunctionOp.MIN_VALUE)) {
            assertEquals(
                MsgType.OMISSION,
                firedType(doc, op, listOf(Expr.Star(UNSIGNED_LINE))),
                "${op.kernelName} over an all-empty unsigned declaration has the fillable zero identity",
            )
        }
    }

    @Test fun multiDeclarationSumUsesTheSignednessOfTheMissingDeclaration() {
        val model = ModelDef(
            fields = listOf(
                FieldDef(ANCHOR, Kind.STRING),
                FieldDef(SIGNED_ROOT, Kind.NUMBER),
                FieldDef(UNSIGNED_ROOT, Kind.NUMBER, signed = false),
            ),
            rules = emptyList(),
        )
        val args = listOf(Expr.Field(SIGNED_ROOT), Expr.Field(UNSIGNED_ROOT))

        val missingUnsigned = model.newDocument().field(ANCHOR, "present", 1, 1).field(SIGNED_ROOT, "5", 1, 1)
        assertEquals(MsgType.VALUE, firedType(missingUnsigned, FunctionOp.SUM, args),
            "a present Sum with only an unsigned declaration missing can grow but cannot shrink")

        val missingSigned = model.newDocument().field(ANCHOR, "present", 1, 1).field(UNSIGNED_ROOT, "5", 1, 1)
        assertEquals(MsgType.OMISSION, firedType(missingSigned, FunctionOp.SUM, args),
            "the same numeric result can shrink when the missing declaration is signed")
    }

    @Test fun groupStarSumUsesEachExpandedDeclarationsMissingnessAndSignedness() {
        val model = ModelDef(
            fields = listOf(
                FieldDef(ANCHOR, Kind.STRING),
                FieldDef(SIGNED_LINE, Kind.NUMBER),
                FieldDef(UNSIGNED_LINE, Kind.NUMBER, signed = false),
            ),
            rules = emptyList(),
            repeatableGroups = setOf(LINES),
            declaredReps = mapOf(LINES to 1),
        )
        val args = listOf(Expr.Star(LINES))

        val missingUnsigned = model.newDocument().field(ANCHOR, "present", 1, 1)
            .field(SIGNED_LINE, "5", 1, 1, 1)
        assertEquals(MsgType.VALUE, firedType(missingUnsigned, FunctionOp.SUM, args),
            "group expansion attributes the missing cell to the unsigned declaration")

        val missingSigned = model.newDocument().field(ANCHOR, "present", 1, 1)
            .field(UNSIGNED_LINE, "5", 1, 1, 1)
        assertEquals(MsgType.OMISSION, firedType(missingSigned, FunctionOp.SUM, args),
            "group expansion attributes the missing cell to the signed declaration")
    }

    @Test fun groupStarExpansionFollowsModelDeclarationOrder() {
        val z = "$LINES/Zeta"
        val a = "$LINES/Alpha"
        val m = "$LINES/Middle"
        val model = ModelDef(
            fields = listOf(FieldDef(ANCHOR, Kind.STRING), FieldDef(z, Kind.NUMBER), FieldDef(a, Kind.NUMBER), FieldDef(m, Kind.NUMBER)),
            rules = emptyList(),
            repeatableGroups = setOf(LINES),
            declaredReps = mapOf(LINES to 1),
        )
        val doc = model.newDocument().field(ANCHOR, "present", 1, 1)
        val ctx = doc.contextsFor(ROOT).single()

        assertEquals(
            listOf(z, a, m),
            ctx.containedFields(LINES),
            "group expansion keeps Zeta, Alpha, Middle model order rather than lexical path order",
        )
    }

    private fun firedType(doc: Document, op: FunctionOp, args: List<Expr>): MsgType? {
        val ctx = doc.contextsFor(ROOT).single()
        val condition = Cond.Cmp(CmpOp.GE, Expr.Func(op.kernelName, args), Expr.NumLit(DEC_ZERO))
        return Interpreter(ctx).firedType(condition)
    }

    private fun num(value: String): Val.Num = Val.Num(dec(value))

    private companion object {
        const val ROOT = "/Invoice"
        const val ANCHOR = "/Invoice/Anchor"
        const val SIGNED_ROOT = "/Invoice/Signed"
        const val UNSIGNED_ROOT = "/Invoice/Unsigned"
        const val LINES = "/Invoice/Lines"
        const val SIGNED_LINE = "/Invoice/Lines/Signed"
        const val UNSIGNED_LINE = "/Invoice/Lines/Unsigned"
    }
}
