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

    /** `<cond>` over ScheduledAt=[sa], CompletedAt=[ca] — kernel and interpreter must agree on firing. */
    private fun agree(cond: String, sa: String, ca: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../ScheduledAt", "TA", cond), "TA",
            RuntimeLaws.doc().group("/Order", 1).field(SA, sa, 1, 1).field(CA, ca, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "TA", "../ScheduledAt")))
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats).field(SA, sa, 1, 1).field(CA, ca, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "TA" }
        assertEquals(k, i, "$msg (sa=$sa ca=$ca)")
    }

    // ---- Add{Hours,Minutes,Seconds}: 22:00:00 + delta carries across midnight ----------------------------
    private val lateEve = "2024-06-15T22:00:00"
    private val anyCa = "2024-06-16T03:30:15"

    @Test fun addHoursCarriesDay() = agree("FieldFilled(ScheduledAt) And DayFromDate(DateFromDateTime(AddHours(ScheduledAt, 5))) == 16", lateEve, anyCa, "22:00 +5h → next day")
    @Test fun addHoursCarriesHour() = agree("FieldFilled(ScheduledAt) And HoursFromTime(TimeFromDateTime(AddHours(ScheduledAt, 5))) == 3", lateEve, anyCa, "22:00 +5h → 03:00")
    @Test fun addMinutes() = agree("FieldFilled(ScheduledAt) And HoursFromTime(TimeFromDateTime(AddMinutes(ScheduledAt, 90))) == 23", lateEve, anyCa, "22:00 +90m → 23:30")
    @Test fun addSeconds() = agree("FieldFilled(ScheduledAt) And SecondsFromTime(TimeFromDateTime(AddSeconds(ScheduledAt, 75))) == 15", lateEve, anyCa, "22:00:00 +75s → :01:15")

    // ---- DifferenceIn{Hours,Minutes,Seconds}: b − a, truncated toward zero ------------------------------
    private val a = "2024-06-15T22:00:00"
    private val b = "2024-06-16T03:30:15"   // a + 5h 30m 15s

    @Test fun diffHours() = agree("FieldFilled(ScheduledAt) And DifferenceInHours(ScheduledAt, CompletedAt) == 5", a, b, "5h30m15s → 5 hours (floor)")
    @Test fun diffMinutes() = agree("FieldFilled(ScheduledAt) And DifferenceInMinutes(ScheduledAt, CompletedAt) == 330", a, b, "5h30m15s → 330 minutes")
    @Test fun diffSeconds() = agree("FieldFilled(ScheduledAt) And DifferenceInSeconds(ScheduledAt, CompletedAt) == 19815", a, b, "→ 19815 seconds")
    @Test fun diffHoursReverseTruncatesTowardZero() = agree("FieldFilled(ScheduledAt) And DifferenceInHours(CompletedAt, ScheduledAt) == -5", a, b, "reverse −5.5h → −5, not −6 (truncate toward zero)")
    @Test fun diffMinutesReverseTruncatesTowardZero() = agree("FieldFilled(ScheduledAt) And DifferenceInMinutes(CompletedAt, ScheduledAt) == -330", a, b, "reverse −330.25m → −330")

    private companion object {
        const val SA = "/Order/ScheduledAt"
        const val CA = "/Order/CompletedAt"
    }
}
