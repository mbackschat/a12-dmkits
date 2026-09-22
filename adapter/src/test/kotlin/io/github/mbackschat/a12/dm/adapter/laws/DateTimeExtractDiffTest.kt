package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `DateFromDateTime` / `TimeFromDateTime` over a DATE_TIME field (`ScheduledAt`,
 * `yyyy-MM-dd'T'HH:mm:ss`). They truncate the date-time to its DATE / TIME part, which then composes with
 * the date extractors (`YearFromDate`/`MonthFromDate`/`DayFromDate`) and the time extractors
 * (`HoursFromTime`/`MinutesFromTime`/`SecondsFromTime`). Fully deterministic — no clock. Kernel-probed.
 */
class DateTimeExtractDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits absolute paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** `<cond>` over ScheduledAt = `instant` — kernel and interpreter must agree on firing. */
    private fun agree(cond: String, instant: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../ScheduledAt", "DX", cond), "DX",
            RuntimeLaws.doc().group("/Order", 1).field(SA, instant, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "DX", "../ScheduledAt")))
        val iDoc = def.newDocument().field(SA, instant, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "DX" }
        assertEquals(k, i, "$msg ($instant)")
    }

    private val instant = "2024-06-15T10:30:45"

    // DateFromDateTime → the date part, read by the date extractors.
    @Test fun yearFromDatePart() = agree(render(m.dateTime(SA).filled().and(Dsl.yearFromDate(Dsl.dateFromDateTime(m.dateTime(SA))).eq(2024))), instant, "year 2024")
    @Test fun yearMismatch() = agree(render(m.dateTime(SA).filled().and(Dsl.yearFromDate(Dsl.dateFromDateTime(m.dateTime(SA))).eq(2023))), instant, "year not 2023")
    @Test fun monthFromDatePart() = agree(render(m.dateTime(SA).filled().and(Dsl.monthFromDate(Dsl.dateFromDateTime(m.dateTime(SA))).eq(6))), instant, "month 6")
    @Test fun dayFromDatePart() = agree(render(m.dateTime(SA).filled().and(Dsl.dayFromDate(Dsl.dateFromDateTime(m.dateTime(SA))).eq(15))), instant, "day 15")

    // TimeFromDateTime → the time part, read by the time extractors.
    @Test fun hoursFromTimePart() = agree(render(m.dateTime(SA).filled().and(Dsl.hoursFromTime(Dsl.timeFromDateTime(m.dateTime(SA))).eq(10))), instant, "hours 10")
    @Test fun hoursMismatch() = agree(render(m.dateTime(SA).filled().and(Dsl.hoursFromTime(Dsl.timeFromDateTime(m.dateTime(SA))).eq(11))), instant, "hours not 11")
    @Test fun minutesFromTimePart() = agree(render(m.dateTime(SA).filled().and(Dsl.minutesFromTime(Dsl.timeFromDateTime(m.dateTime(SA))).eq(30))), instant, "minutes 30")
    @Test fun secondsFromTimePart() = agree(render(m.dateTime(SA).filled().and(Dsl.secondsFromTime(Dsl.timeFromDateTime(m.dateTime(SA))).eq(45))), instant, "seconds 45")

    private companion object {
        const val SA = "/Order/ScheduledAt"
    }
}
