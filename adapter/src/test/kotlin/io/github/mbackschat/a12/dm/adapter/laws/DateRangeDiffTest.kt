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
 * Differential — the **date-range endpoint extractors** `StartOfDateRange` / `EndOfDateRange` over a
 * `DateRangeType` field (`CoverageWindow`, format `dd.MM.yyyy`, separator `-`). The interpreter must parse
 * the rendered range value (`01.06.2021-30.06.2021`) into its two ISO endpoints (format-aware, so the date
 * extractors read them like any date) and agree with the kernel on firing. (`DateRangesOverlap` and the
 * starred/`IN` forms are a follow-up; the format foundation lands here.)
 */
class DateRangeDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(OD, Kind.DATE),
        FieldDef(CW, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
    )

    /** `<cond>` over OrderDate filled + CoverageWindow = `coverage` — kernel and interpreter must agree on firing. */
    private fun agree(cond: String, coverage: String, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "RR", "../OrderDate", "RR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/RR", "/Order", cond, "RR", "../OrderDate")))

        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(OD, "2024-06-01", 1, 1).field(CW, coverage, 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig)
            .field(OD, "2024-06-01", 1, 1).field(CW, coverage, 1, 1)
        val kernel = RuntimeLaws.fires(kModel, "RR", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "RR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val cov = "01.06.2021-30.06.2021"   // 1 Jun 2021 .. 30 Jun 2021

    @Test fun startYearFires() = agree("FieldFilled(OrderDate) And YearFromDate(StartOfDateRange(CoverageWindow)) > 2020", cov, "start year 2021 > 2020")
    @Test fun startYearDoesNotFire() = agree("FieldFilled(OrderDate) And YearFromDate(StartOfDateRange(CoverageWindow)) > 2021", cov, "start year 2021 not > 2021")
    @Test fun startMonth() = agree("FieldFilled(OrderDate) And MonthFromDate(StartOfDateRange(CoverageWindow)) == 6", cov, "start month is 6")
    @Test fun endDayFires() = agree("FieldFilled(OrderDate) And DayFromDate(EndOfDateRange(CoverageWindow)) > 28", cov, "end day 30 > 28")
    @Test fun endDayDoesNotFire() = agree("FieldFilled(OrderDate) And DayFromDate(EndOfDateRange(CoverageWindow)) > 30", cov, "end day 30 not > 30")

    private companion object {
        const val OD = "/Order/OrderDate"
        const val CW = "/Order/CoverageWindow"
    }
}
