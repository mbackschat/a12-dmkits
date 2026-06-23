package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
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

    /** A starred-aggregate rule at /Cart over two Lines (Price `p1`, `p2`) — kernel and interpreter must agree. */
    private fun starred(cond: String, code: String, p1: String, p2: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart", code, "../Lines/Price", code, cond)
        val def = model(RuleDef("/Cart/$code", "/Cart", cond, code, "../Lines/Price"))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1).field(PRICE, p1, 1, 1, 1)
            .group("/Cart/Lines", 1, 2).field(ITEM, "B", 1, 2, 1).field(PRICE, p2, 1, 2, 1)
        val iDoc = doc(def).field(ITEM, "A", 1, 1, 1).field(PRICE, p1, 1, 1, 1)
            .field(ITEM, "B", 1, 2, 1).field(PRICE, p2, 1, 2, 1)
        assertAgree(code, kModel, kDoc, def, iDoc)
    }

    /** An operand-list `Min([Qty], [Price])` rule on one Line — kernel and interpreter must agree. */
    private fun operandMin(qty: String, price: String) {
        val cond = "Min([Qty], [Price]) > 0"
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "OM", "../Qty", "OM", cond)
        val def = model(RuleDef("/Cart/Lines/OM", "/Cart/Lines", cond, "OM", "../Qty"))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val iDoc = doc(def).field(ITEM, "A", 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        assertAgree("OM", kModel, kDoc, def, iDoc)
    }

    private fun assertAgree(code: String, kModel: String, kDoc: RuntimeLaws.Doc, def: ModelDef, iDoc: Document) {
        val relevant = { c: String -> c == code || c.startsWith("zahl") }
        val kernel = RuntimeLaws.messages(kModel, kDoc).map { it.code }.filter(relevant).sorted()
        val interp = DmInterpreter(def).validateFull(iDoc).map { it.code }.filter(relevant).sorted()
        assertEquals(kernel, interp, "$code  (kernel=$kernel interp=$interp)")
    }

    private fun model(rule: RuleDef) = ModelDef(
        fields, listOf(rule), repeatableGroups = setOf("/Cart/Lines"),
        declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM),
    )

    private fun doc(def: ModelDef) = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)

    @Test fun sumSuppressedByMalformedElement() = starred("Sum(Lines*/Price) > 100", "SM", "200", "abc")
    @Test fun sumComputesWhenAllValid() = starred("Sum(Lines*/Price) > 100", "SM", "200", "50")
    @Test fun maxValueSuppressedByMalformedElement() = starred("MaxValue(Lines*/Price) > 100", "MX", "200", "abc")
    @Test fun minValueSuppressedByMalformedElement() = starred("MinValue(Lines*/Price) > 0", "MN", "200", " 5 ")
    @Test fun distinctSuppressedByMalformedElement() = starred("NumberOfDifferentValues(Lines*/Price) > 1", "ND", "200", "abc")
    @Test fun minOperandSuppressedByMalformedOperand() = operandMin("abc", "50")
    @Test fun minOperandComputesWhenAllValid() = operandMin("10", "50")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val PRICE = "/Cart/Lines/Price"
    }
}
