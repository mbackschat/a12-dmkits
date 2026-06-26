package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
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
 * Differential — the `Having` per-row filter on a starred aggregate: the aggregate consumes only the rows for
 * which the condition holds. Rows (UnitWeight, Count): (1,3),(2,10),(1,4) → Sum of Count over W==1 rows = 7
 * (unfiltered = 17). Filters on a NON-index field (UnitWeight) so multiple rows match without uniqueIndex
 * noise (Sku kept distinct). Kernel-probed.
 */
class HavingDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(CN, Kind.STRING), FieldDef(SKU, Kind.STRING),
        FieldDef(WEIGHT, Kind.NUMBER, scale = 2), FieldDef(COUNT, Kind.NUMBER))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `Dsl.sum(flatten.having(...))` is the per-row Having filter.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private val itemCounts = m.flatten("/Order/Items*/Count")
    private fun filledCn() = Dsl.fieldFilled(m.field(CN))
    /** Sum of the starred Items Count list, filtered to rows where UnitWeight == [w] — the per-row Having aggregate. */
    private fun filteredSum(w: Long) = Dsl.sum(itemCounts.having(m.num(WEIGHT).eq(w)))

    /** [cond] over Items rows = [rows] (weight, count) — kernel and interpreter agree on firing. */
    private fun agree(cond: String, rows: List<Pair<String, String>>, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "HV", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "HV", "../CustomerName")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(SKU))
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats, def.datePrecisions).field(CN, "Acme", 1, 1)
        rows.forEachIndexed { i, (w, c) ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r).field(SKU, "K$r", 1, r, 1).field(WEIGHT, w, 1, r, 1).field(COUNT, c, 1, r, 1)
            iDoc.group("/Order/Items", 1, r).field(SKU, "K$r", 1, r, 1).field(WEIGHT, w, 1, r, 1).field(COUNT, c, 1, r, 1)
        }
        val k = RuntimeLaws.fires(kModel, "HV", kDoc)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "HV" }
        assertEquals(k, i, "$msg (kernel=$k interp=$i)")
    }

    private val rows = listOf("1" to "3", "2" to "10", "1" to "4")   // W==1 rows have Count 3 + 4 = 7

    @Test fun sumOverFilteredRowsFires() = agree(render(filledCn().and(filteredSum(1).gt(5))), rows, "filtered Sum 7 > 5")
    @Test fun sumOverFilteredRowsBoundary() = agree(render(filledCn().and(filteredSum(1).gt(7))), rows, "filtered Sum 7 not > 7")
    @Test fun sumOverFilteredRowsEquals() = agree(render(filledCn().and(filteredSum(1).eq(7))), rows, "filtered Sum == 7")
    @Test fun noMatchingRowsSumZero() = agree(render(filledCn().and(filteredSum(9).eq(0))), rows, "no W==9 rows → filtered Sum 0")
    @Test fun unfilteredSumDiffers() = agree(render(filledCn().and(Dsl.sum(itemCounts).eq(17))), rows, "the UNfiltered Sum is 17 (all rows)")

    private companion object {
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val WEIGHT = "/Order/Items/UnitWeight"
        const val COUNT = "/Order/Items/Count"
    }
}
