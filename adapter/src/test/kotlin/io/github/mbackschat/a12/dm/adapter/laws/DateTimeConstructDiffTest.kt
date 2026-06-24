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
 * Differential — `DateTime(date, time)` composing a DATE field (`OrderDate`, `yyyy-MM-dd`) and a TIME field
 * (`PickupTime`, `HH:mm:ss`) into a DATE_TIME. Verified by round-tripping through the extractors, and by
 * comparing the composed value to a DATE_TIME field (`ScheduledAt`). Fully deterministic — no clock.
 */
class DateTimeConstructDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(OD, Kind.DATE), FieldDef(PT, Kind.TIME, format = "HH:mm:ss"),
        FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
    )

    /** `<cond>` over OrderDate=[date], PickupTime=[time], ScheduledAt=[sched] — kernel and interp agree on firing. */
    private fun agree(cond: String, date: String, time: String, sched: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../OrderDate", "DC", cond), "DC",
            RuntimeLaws.doc().group("/Order", 1).field(OD, date, 1, 1).field(PT, time, 1, 1).field(SA, sched, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "DC", "../OrderDate")))
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats).field(OD, date, 1, 1).field(PT, time, 1, 1).field(SA, sched, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "DC" }
        assertEquals(k, i, "$msg (date=$date time=$time sched=$sched)")
    }

    @Test fun composedDateRoundTrips() =
        agree("FieldFilled(OrderDate) And DayFromDate(DateFromDateTime(DateTime(OrderDate, PickupTime))) == 15",
            "2024-06-15", "10:30:45", "2024-06-15T10:30:45", "composed date's day is 15")

    @Test fun composedTimeRoundTrips() =
        agree("FieldFilled(OrderDate) And HoursFromTime(TimeFromDateTime(DateTime(OrderDate, PickupTime))) == 10",
            "2024-06-15", "10:30:45", "2024-06-15T10:30:45", "composed time's hour is 10")

    @Test fun composedEqualsDateTimeField() =
        agree("FieldFilled(OrderDate) And [ScheduledAt] == DateTime(OrderDate, PickupTime)",
            "2024-06-15", "10:30:45", "2024-06-15T10:30:45", "the composed date-time equals ScheduledAt")

    @Test fun composedDiffersFromDateTimeField() =
        agree("FieldFilled(OrderDate) And [ScheduledAt] == DateTime(OrderDate, PickupTime)",
            "2024-06-15", "10:30:45", "2024-06-15T09:00:00", "a different ScheduledAt does not equal the composition")

    @Test fun composedAfterDateTimeField() =
        agree("FieldFilled(OrderDate) And DateTime(OrderDate, PickupTime) > [ScheduledAt]",
            "2024-06-15", "10:30:45", "2024-06-15T09:00:00", "the composition is after an earlier ScheduledAt")

    private companion object {
        const val OD = "/Order/OrderDate"
        const val PT = "/Order/PickupTime"
        const val SA = "/Order/ScheduledAt"
    }
}
