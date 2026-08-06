package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a **numeric combiner with a malformed element** (KERNEL-FINDINGS §3/§1). Surfaced by
 * `InterpreterKernelFuzzTest`: a malformed NUMBER reached through a starred aggregate (or an operand-list
 * `Min`/`Max`) made the interpreter **crash** (`dec("abc")`), where the kernel makes the whole combiner
 * **non-evaluable** — it emits the field's formal error and **suppresses** the referencing rule (probed: a
 * `Sum` with one `"abc"` element fires only `zahlHatUngueltigeZeichen`, not the rule). The interpreter now
 * parses defensively (malformed → `Val.Unknown`) and propagates that: any `Unknown` element → the combiner is
 * `Unknown` → the rule is suppressed. Verified for every starred aggregate + an operand-list `Min`, both with
 * a malformed element AND all-valid (the combiner still computes). Compared on the rule code + `zahl…` formals.
 */
class AggMalformedDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
    )

    // The numeric-combiner conditions are authored with the rulekit typed DSL ([m]) and rendered to the
    // canonical string fed to BOTH engines — no free-text condition literals. The MALFORMED operand values
    // (e.g. "abc") stay in the doc-building below; only the condition authoring changes.
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private val prices = m.flatten("/Cart/Lines*/Price")

    /** A starred-aggregate rule at /Cart over two Lines (Price `p1`, `p2`) — the two engines' code lists. */
    private fun starredCase(cond: String, code: String, p1: String, p2: String): Pair<List<String>, List<String>> {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart", code, "../Lines/Price", code, cond)
        val def = model(RuleDef("/Cart/$code", "/Cart", cond, code, "../Lines/Price"))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1).field(PRICE, p1, 1, 1, 1)
            .group("/Cart/Lines", 1, 2).field(ITEM, "B", 1, 2, 1).field(PRICE, p2, 1, 2, 1)
        val iDoc = doc(def).field(ITEM, "A", 1, 1, 1).field(PRICE, p1, 1, 1, 1)
            .field(ITEM, "B", 1, 2, 1).field(PRICE, p2, 1, 2, 1)
        return codes(code, kModel, kDoc, def, iDoc)
    }

    /** A starred-aggregate rule at /Cart over two Lines (Price `p1`, `p2`) — kernel and interpreter must agree. */
    private fun starred(cond: String, code: String, p1: String, p2: String) {
        val (kernel, interp) = starredCase(cond, code, p1, p2)
        assertEquals(kernel, interp, "$code  (kernel=$kernel interp=$interp)")
    }

    /** An operand-list `Min([Qty], [Price])` rule on one Line — kernel and interpreter must agree. */
    private fun operandMin(qty: String, price: String) {
        val cond = render(Dsl.min(m.num(QTY).asNumber(), m.num(PRICE).asNumber()).gt(0))
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "OM", "../Qty", "OM", cond)
        val def = model(RuleDef("/Cart/Lines/OM", "/Cart/Lines", cond, "OM", "../Qty"))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val iDoc = doc(def).field(ITEM, "A", 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        assertAgree("OM", kModel, kDoc, def, iDoc)
    }

    /** The rule code plus the NUMBER formals, as the dynamic kernel and the interpreter each report them. */
    private fun codes(code: String, kModel: String, kDoc: RuntimeLaws.Doc, def: ModelDef, iDoc: Document):
        Pair<List<String>, List<String>> {
        val relevant = { c: String -> c == code || c.startsWith("zahl") }
        return RuntimeLaws.messages(kModel, kDoc).map { it.code }.filter(relevant).sorted() to
            DmInterpreter(def).validateFull(iDoc).map { it.code }.filter(relevant).sorted()
    }

    private fun assertAgree(code: String, kModel: String, kDoc: RuntimeLaws.Doc, def: ModelDef, iDoc: Document) {
        val (kernel, interp) = codes(code, kModel, kDoc, def, iDoc)
        assertEquals(kernel, interp, "$code  (kernel=$kernel interp=$interp)")
    }

    private fun model(rule: RuleDef) = ModelDef(
        fields, listOf(rule), repeatableGroups = setOf("/Cart/Lines"),
        declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM),
    )

    private fun doc(def: ModelDef) = def.newDocument()

    @Test fun sumSuppressedByMalformedElement() = starred(render(Dsl.sum(prices).gt(100)), "SM", "200", "abc")
    @Test fun sumComputesWhenAllValid() = starred(render(Dsl.sum(prices).gt(100)), "SM", "200", "50")
    @Test fun maxValueSuppressedByMalformedElement() = starred(render(Dsl.maxValue(prices).gt(100)), "MX", "200", "abc")
    @Test fun minValueSuppressedByMalformedElement() = starred(render(Dsl.minValue(prices).gt(0)), "MN", "200", " 5 ")
    @Test fun distinctSuppressedByMalformedElement() = starred(render(Dsl.numberOfDifferentValues(prices).gt(1)), "ND", "200", "abc")
    @Test fun minOperandSuppressedByMalformedOperand() = operandMin("abc", "50")
    @Test fun minOperandComputesWhenAllValid() = operandMin("10", "50")

    /**
     * The POSITIONAL discriminator for the ordered account of `combineFeldListe` (KERNEL-FINDINGS §3): the scan
     * early-returns at the FIRST non-relevant cell rather than surveying the selection, so for an **all-rows**
     * aggregate the malformed cell's authored position cannot be read off validation output — head and tail must
     * produce the identical verdict, and that verdict is the formal error ALONE (the rule stays suppressed).
     *
     * This measures the *negative* half of the mechanism. What makes the ordering a real mechanism rather than a
     * restatement is the rival stop-at-first combiner, where position IS observable in validation:
     * `FirstFilledValueDiffTest.malformedAfterFirstIsInvisible` FIRES on the very shape that suppresses here,
     * while `malformedAtFirstFilledSuppresses` does not. A whole-selection scan cannot produce that pair.
     */
    @Test fun theMalformedCellsPositionDoesNotChangeAnAllRowsAggregateVerdict() {
        val rows = mutableListOf<String>()
        for ((code, cond) in listOf(
            "SM" to render(Dsl.sum(prices).gt(100)),
            "MX" to render(Dsl.maxValue(prices).gt(100)),
            "MN" to render(Dsl.minValue(prices).gt(0)),
            "ND" to render(Dsl.numberOfDifferentValues(prices).gt(1)),
        )) {
            val (headKernel, headInterp) = starredCase(cond, code, "abc", "200")
            val (tailKernel, tailInterp) = starredCase(cond, code, "200", "abc")
            // The POSITIVE CONTROL, in the same row: with both cells valid the rule code must SURFACE through
            // the very filter the suppressed rows are read through — otherwise the two rows above would agree
            // vacuously, for want of any channel that could have shown the rule at all.
            val (validKernel, validInterp) = starredCase(cond, code, "200", "50")
            assertEquals(headKernel, headInterp, "$code malformed head — kernel/interpreter")
            assertEquals(tailKernel, tailInterp, "$code malformed tail — kernel/interpreter")
            assertEquals(validKernel, validInterp, "$code all valid — kernel/interpreter")
            rows += "$code head=$headKernel tail=$tailKernel valid=$validKernel"
        }
        assertEquals(
            listOf(
                "SM head=[zahlHatUngueltigeZeichen] tail=[zahlHatUngueltigeZeichen] valid=[SM]",
                "MX head=[zahlHatUngueltigeZeichen] tail=[zahlHatUngueltigeZeichen] valid=[MX]",
                "MN head=[zahlHatUngueltigeZeichen] tail=[zahlHatUngueltigeZeichen] valid=[MN]",
                "ND head=[zahlHatUngueltigeZeichen] tail=[zahlHatUngueltigeZeichen] valid=[ND]",
            ),
            rows,
            "position must not select the verdict, and the rule must stay suppressed in both orders",
        )
    }

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val PRICE = "/Cart/Lines/Price"
    }
}
