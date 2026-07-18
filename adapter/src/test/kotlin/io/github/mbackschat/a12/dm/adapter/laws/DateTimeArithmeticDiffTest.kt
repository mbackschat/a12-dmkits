package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the date-time arithmetic family over DATE_TIME fields (`ScheduledAt`, `CompletedAt`,
 * `yyyy-MM-dd'T'HH:mm:ss`): `Add{Hours,Minutes,Seconds}` (verified through the extractors, with a day-boundary
 * carry) and `DifferenceIn{Hours,Minutes,Seconds}` (`(b−a)/unit`). The sign cases lock the kernel-probed
 * **truncation toward zero**: a reverse difference of −5.5 units is −5, NOT −6. Fully deterministic — no clock.
 */
class DateTimeArithmeticDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(CA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. ABSOLUTE paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** `<cond>` over ScheduledAt=[sa], CompletedAt=[ca] — kernel and interpreter must agree on firing. */
    private fun agree(cond: String, sa: String, ca: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../ScheduledAt", "TA", cond), "TA",
            RuntimeLaws.doc().group("/Order", 1).field(SA, sa, 1, 1).field(CA, ca, 1, 1))
        // timeZone mirrors the kernel fixture's Europe/Berlin (order.dm.json modelConfig) — inert for this
        // file's non-straddling June values; the DST family lives in DstTimeZoneDiffTest.
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "TA", "../ScheduledAt")),
            timeZone = ModelTimeZone.EUROPE_BERLIN)
        val iDoc = def.newDocument().field(SA, sa, 1, 1).field(CA, ca, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "TA" }
        assertEquals(k, i, "$msg (sa=$sa ca=$ca)")
    }

    // ---- Add{Hours,Minutes,Seconds}: 22:00:00 + delta carries across midnight ----------------------------
    private val lateEve = "2024-06-15T22:00:00"
    private val anyCa = "2024-06-16T03:30:15"

    // sub-day shifts now yield a DateTimeExpr, which the (widened) DateFromDateTime/TimeFromDateTime extractors
    // accept — so the intermediate `DateFromDateTime(AddHours(...))` composition is expressible (DG9).
    @Test fun addHoursCarriesDay() = agree(render(m.dateTime(SA).filled().and(Dsl.dayFromDate(Dsl.dateFromDateTime(Dsl.addHours(m.dateTime(SA), 5))).eq(16))), lateEve, anyCa, "22:00 +5h → next day")
    @Test fun addHoursCarriesHour() = agree(render(m.dateTime(SA).filled().and(Dsl.hoursFromTime(Dsl.timeFromDateTime(Dsl.addHours(m.dateTime(SA), 5))).eq(3))), lateEve, anyCa, "22:00 +5h → 03:00")
    @Test fun addMinutes() = agree(render(m.dateTime(SA).filled().and(Dsl.hoursFromTime(Dsl.timeFromDateTime(Dsl.addMinutes(m.dateTime(SA), 90))).eq(23))), lateEve, anyCa, "22:00 +90m → 23:30")
    @Test fun addSeconds() = agree(render(m.dateTime(SA).filled().and(Dsl.secondsFromTime(Dsl.timeFromDateTime(Dsl.addSeconds(m.dateTime(SA), 75))).eq(15))), lateEve, anyCa, "22:00:00 +75s → :01:15")

    // ---- DifferenceIn{Hours,Minutes,Seconds}: b − a, truncated toward zero ------------------------------
    private val a = "2024-06-15T22:00:00"
    private val b = "2024-06-16T03:30:15"   // a + 5h 30m 15s

    @Test fun diffHours() = agree(render(m.dateTime(SA).filled().and(Dsl.differenceInHours(m.dateTime(SA), m.dateTime(CA)).eq(5))), a, b, "5h30m15s → 5 hours (floor)")
    @Test fun diffMinutes() = agree(render(m.dateTime(SA).filled().and(Dsl.differenceInMinutes(m.dateTime(SA), m.dateTime(CA)).eq(330))), a, b, "5h30m15s → 330 minutes")
    @Test fun diffSeconds() = agree(render(m.dateTime(SA).filled().and(Dsl.differenceInSeconds(m.dateTime(SA), m.dateTime(CA)).eq(19815))), a, b, "→ 19815 seconds")
    @Test fun diffHoursReverseTruncatesTowardZero() = agree(render(m.dateTime(SA).filled().and(Dsl.differenceInHours(m.dateTime(CA), m.dateTime(SA)).eq(-5))), a, b, "reverse −5.5h → −5, not −6 (truncate toward zero)")
    @Test fun diffMinutesReverseTruncatesTowardZero() = agree(render(m.dateTime(SA).filled().and(Dsl.differenceInMinutes(m.dateTime(CA), m.dateTime(SA)).eq(-330))), a, b, "reverse −330.25m → −330")

    private companion object {
        const val SA = "/Order/ScheduledAt"
        const val CA = "/Order/CompletedAt"
    }
}
