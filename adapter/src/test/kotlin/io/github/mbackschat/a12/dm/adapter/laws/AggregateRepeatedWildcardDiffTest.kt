package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — SPEC-2026-07-22-07: a repeated wildcard operand is EXPANDED AND CONSUMED AGAIN at each
 * authored slot, never deduplicated. `Sum(Lines* / Qty, Lines* / Qty)` over rows `Qty = [2, 3]` sums each
 * slot independently → `10`, not the single-slot `5`. The kernel skips wildcarded refs in its duplicate
 * gate (`checkDuplicatesInDirectRefs` continues past `ref.hasWildCards()` outside HModel), so the model is
 * legal and every occurrence contributes. Both kernel strategies and the interpreter must agree on firing.
 */
class AggregateRepeatedWildcardDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0),
    )
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Place one Line per [qtys] entry (ItemId always filled; Qty placed when non-null), run the `/Cart`-level
     *  aggregate rule, and assert both engines fire identically (firing AND polarity; null = no fire). */
    private fun agree(cond: String, qtys: List<String?>, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart", "AGG", "../Lines/Qty", "AGG", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/AGG", "/Cart", cond, "AGG", "../Lines/Qty")),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
        val iDoc = def.newDocument()
        qtys.forEachIndexed { i, qty ->
            val row = i + 1
            kDoc.group("/Cart/Lines", 1, row).field(ITEM, "K$row", 1, row, 1)
            iDoc.field(ITEM, "K$row", 1, row, 1)
            if (qty != null) { kDoc.field(QTY, qty, 1, row, 1); iDoc.field(QTY, qty, 1, row, 1) }
        }
        val kernel = RuntimeLaws.typeOf(kModel, "AGG", kDoc).map { it.name }.orElse(null)
        val interp = DmInterpreter(def).validateFull(iDoc).firstOrNull { it.code == "AGG" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val dup = m.flatten("/Cart/Lines*/Qty")

    // rows [2, 3]: a single-slot Sum is 5; two identical wildcard slots make it 10.
    private val dupGe10 = render(Dsl.sum(dup, dup).ge(10))
    private val dupGt10 = render(Dsl.sum(dup, dup).gt(10))
    private val singleGe10 = render(Dsl.sum(dup).ge(10))

    @Test fun repeatedWildcardIsConsumedTwice() =
        agree(dupGe10, listOf("2", "3"), "Sum(dup, dup) = 10 >= 10 fires")

    @Test fun doubledValueIsExactlyTen() =
        agree(dupGt10, listOf("2", "3"), "Sum(dup, dup) = 10 is not > 10 → no fire")

    @Test fun singleSlotIsNotDoubled() =
        agree(singleGe10, listOf("2", "3"), "Sum(single) = 5 is not >= 10 → no fire (proves the doubling)")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
    }
}
