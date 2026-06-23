package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the date-component extractors `YearFromDate` / `MonthFromDate` / `DayFromDate` /
 * `QuarterFromDate` (a number from the date's parts), used inside a comparison. The interpreter's verdict
 * must match the kernel for several `OrderDate` values. Driven from parsed text; the date field is filled
 * (the catalog guards each with `FieldFilled`).
 */
class DateExtractDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(OD, Kind.DATE))

    private fun agree(cond: String, date: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../OrderDate", "DX", cond), "DX",
            RuntimeLaws.doc().group("/Order", 1).field(OD, date, 1, 1))
        val i = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "DX", "../OrderDate"))))
            .validate(Document(kinds).field(OD, date, 1, 1)).messages.any { it.code == "DX" }
        assertEquals(k, i, "$msg ($date)")
    }

    @Test fun yearFromDate() {
        val c = "YearFromDate(OrderDate) < 2020"
        agree(c, "2019-06-15", "2019 < 2020 fires"); agree(c, "2021-06-15", "2021 not < 2020")
    }

    @Test fun monthFromDate() {
        val c = "MonthFromDate(OrderDate) == 12"
        agree(c, "2020-12-25", "Dec fires"); agree(c, "2020-06-15", "Jun no")
    }

    @Test fun dayFromDate() {
        val c = "DayFromDate(OrderDate) > 28"
        agree(c, "2020-12-30", "30 > 28 fires"); agree(c, "2020-12-15", "15 no")
    }

    @Test fun quarterFromDate() {
        val c = "QuarterFromDate(OrderDate) == 4"
        agree(c, "2020-11-15", "Q4 fires"); agree(c, "2020-06-15", "Q2 no"); agree(c, "2020-01-15", "Q1 no")
    }

    private val kinds = mapOf(OD to Kind.DATE)

    private companion object {
        const val OD = "/Order/OrderDate"
    }
}
