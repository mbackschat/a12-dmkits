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
 * Differential — **`SumOfProducts(Lines* / Qty, Lines* / Price)`**: Σ over rows of `Qty_i · Price_i`, the two
 * fields **paired row-by-row** (kernel `summeVonProdukten` zips the two iterators). Exact-match catches: an
 * empty cell reads as **0** (so its product is 0); a malformed cell makes the whole sum non-evaluable → the rule
 * is **suppressed**. The firing threshold is chosen so a *mis-pairing* (cross-multiplying rows) would give a
 * different sum and flip the result — the row-alignment catcher. A `/Cart`-level rule over the Line rows.
 */
class SumOfProductsDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
    )

    /** Place one Line per [rows] `(qty, price)` (ItemId always filled, distinct), run the `/Cart`-level rule. */
    private fun agree(cond: String, rows: List<Pair<String?, String?>>, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart", "SP", "../Lines/Qty", "SP", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/SP", "/Cart", cond, "SP", "../Lines/Qty")),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
        rows.forEachIndexed { i, (q, p) ->
            val r = i + 1
            kDoc.group("/Cart/Lines", 1, r).field(ITEM, "K$r", 1, r, 1)
            iDoc.field(ITEM, "K$r", 1, r, 1)
            if (q != null) { kDoc.field(QTY, q, 1, r, 1); iDoc.field(QTY, q, 1, r, 1) }
            if (p != null) { kDoc.field(PRICE, p, 1, r, 1); iDoc.field(PRICE, p, 1, r, 1) }
        }
        val kernel = RuntimeLaws.fires(kModel, "SP", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "SP" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val sp = "SumOfProducts(Lines*/Qty, Lines*/Price)"

    @Test fun pairsRowByRow() =
        agree("$sp > 23", listOf("2" to "3", "4" to "5"), "2·3 + 4·5 = 26 > 23 (a mis-pairing → 22, would not)")
    @Test fun doesNotFireBelowThreshold() =
        agree("$sp > 30", listOf("2" to "3", "4" to "5"), "26 not > 30")
    @Test fun emptyCellCountsAsZero() =
        agree("$sp > 5", listOf("2" to "3", "10" to null), "2·3 + 10·0 = 6 > 5")
    @Test fun malformedCellSuppresses() =
        agree("$sp > 0", listOf("2" to "3", "abc" to "5"), "malformed Qty → sum non-evaluable → suppressed")
    @Test fun singleRow() =
        agree("$sp > 10", listOf("3" to "4"), "3·4 = 12 > 10")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val PRICE = "/Cart/Lines/Price"
    }
}
