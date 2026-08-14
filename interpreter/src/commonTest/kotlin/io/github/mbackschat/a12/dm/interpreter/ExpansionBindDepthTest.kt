package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.expansionBindDepth
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.FunctionOp
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * JVM + JS lock for `ast.expansionBindDepth`, the normalizer that resolves a group operand's `bindDepth = -1`
 * default to the operand's OWN absolute depth before the expansion is bound.
 *
 * **Why this needs a test at all, given it changes no authored behaviour.** A cold review established that `-1` is
 * **unreachable from parsed input**: `Parser.pathFrom` only builds a `Star` when a star is present and its
 * arithmetic is `>= 0` for the absolute, `..`-relative, and walk-up forms alike, and the only `.copy()` sites
 * change `having`. So the normalizer is a **no-op for every authored condition** and exists as hardening for
 * hand-built AST — this module's own tests and the public `Expr` API, whose data classes take the `-1` default the
 * moment a caller omits `bindDepth` (as `Expr.Star(path)` does throughout `commonTest`). [IF287] keeps it on that
 * basis. It had ZERO discriminating tests, which is what this file fixes.
 *
 * **The trap this fixture is built around.** `-1` and the operand's own depth are NOT generally different: the
 * `-1` branch of `Document.groupScopePrefix` caps at the expanded field's parent-group depth, which coincides with
 * the operand's depth whenever exactly ONE repeatable level lies between them. They diverge only with **two**
 * levels below the operand, and only when the EVALUATING context is deep enough for the cap to bite at all — a
 * rule on a shallow root group makes `commonPrefixLen` 1 and the cap never applies. Both conditions are met here,
 * and [aDeeperBindDepthChangesTheAnswerOnThisFixture] proves it by showing the knob is live rather than inert:
 * without that row, the equality below would be satisfied by a fixture on which `bindDepth` does nothing.
 *
 * The contract is asserted as an EQUIVALENCE — the `-1` default must behave exactly as the explicit operand depth
 * — rather than by predicting a concrete sum. That is deliberate: [IF287] records three successive readings of
 * `groupScopePrefix` that each produced a falsified prediction, so anything asserted about it here is asserted as
 * a relation between two runs of the real thing, never as a number derived by hand.
 */
class ExpansionBindDepthTest {

    @Test fun theMinusOneDefaultBehavesAsTheOperandsOwnDepth() {
        val doc = threeLevelDocument()
        assertEquals(
            sumOverGroup(doc, bindDepth = null),
            sumOverGroup(doc, bindDepth = OPERAND_DEPTH),
            "the -1 default must resolve to the operand's own absolute depth ($OPERAND_DEPTH)",
        )
    }

    /**
     * The non-vacuity guard: a bind depth one level DEEPER than the operand gives a different answer on this
     * document, so the equivalence above is a statement about the normalizer and not about a fixture where every
     * bind depth happens to agree.
     */
    @Test fun aDeeperBindDepthChangesTheAnswerOnThisFixture() {
        val doc = threeLevelDocument()
        assertNotEquals(
            sumOverGroup(doc, bindDepth = OPERAND_DEPTH),
            sumOverGroup(doc, bindDepth = OPERAND_DEPTH + 1),
            "bindDepth is inert on this fixture, so the equivalence test above proves nothing",
        )
    }

    /** The pure contract of the normalizer itself, independent of any evaluation. */
    @Test fun theNormalizerPassesAnAbsoluteDepthThroughAndResolvesTheSentinel() {
        assertEquals(2, expansionBindDepth(OUTER, -1),
            "the sentinel resolves to the operand path's own segment count")
        assertEquals(0, expansionBindDepth(OUTER, 0),
            "an absolute 0 is a real bind depth and must survive")
        assertEquals(5, expansionBindDepth(OUTER, 5),
            "an absolute depth is passed through even when it exceeds the operand's own")
    }

    /**
     * THREE stacked repeatable levels below the group operand, with a distinct value in every cell so any change
     * in which rows are reached changes the total. Two levels is not enough, and finding that out is the point:
     * `groupScopePrefix` computes `minOf(parts.size - 1, bindDepth)`, so it CLAMPS — with the field two segments
     * below the operand, every bind depth of 2 or more collapses to the same cap and the knob is inert. The third
     * level is what separates the `-1` branch (which caps at the expanded field's parent-group depth, 3 here) from
     * the operand's own depth (2). Evaluated from a row of the deepest level, since a shallow evaluating context
     * makes `commonPrefixLen` the binding term and the cap never applies at all.
     */
    private fun threeLevelDocument(): Document {
        val model = ModelDef(
            fields = listOf(FieldDef(ANCHOR, Kind.STRING), FieldDef(AMOUNT, Kind.NUMBER)),
            rules = emptyList(),
            repeatableGroups = setOf(OUTER, INNER, DEEPER),
            declaredReps = mapOf(OUTER to 2, INNER to 2, DEEPER to 2),
        )
        var doc = model.newDocument().field(ANCHOR, "present", 1, 1)
        var value = 1
        for (outer in 1..2) {
            for (inner in 1..2) {
                for (deeper in 1..2) {
                    doc = doc.field(AMOUNT, "${value++}", 1, outer, inner, deeper, 1)
                }
            }
        }
        return doc
    }

    /**
     * The SUM is the observable, and it is read WITHOUT predicting it: the smallest bar the total fails to clear
     * identifies the total exactly. Reporting it as a string keeps the two assertions above comparing one
     * measured quantity rather than a hand-derived expectation.
     */
    private fun sumOverGroup(doc: Document, bindDepth: Int?): String {
        val ctx = doc.contextsFor(DEEPER).first()
        val operand = if (bindDepth == null) Expr.Star(OUTER) else Expr.Star(OUTER, null, false, bindDepth)
        val sum = Expr.Func(FunctionOp.SUM.kernelName, listOf(operand))
        val interpreter = Interpreter(ctx)
        for (bar in 0..100) {
            if (interpreter.firedType(Cond.Cmp(CmpOp.GT, sum, Expr.NumLit(dec(bar)))) == null) {
                return "sum<=$bar"
            }
        }
        return "sum>100"
    }

    private companion object {
        const val ANCHOR = "/Doc/Anchor"
        const val OUTER = "/Doc/Outer"
        const val INNER = "/Doc/Outer/Inner"
        const val DEEPER = "/Doc/Outer/Inner/Deeper"
        const val AMOUNT = "/Doc/Outer/Inner/Deeper/Amount"

        /** `/Doc/Outer` has two segments, so the normalizer must resolve `-1` to 2. */
        const val OPERAND_DEPTH = 2
    }
}
