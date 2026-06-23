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
 * Differential — the time-of-day extractors `HoursFromTime` / `MinutesFromTime` / `SecondsFromTime` (a
 * number from a TIME value's parts), used inside a comparison. The interpreter's verdict must match the
 * kernel for several `OnSiteTime` values. A TIME field value is the ISO `HH:mm:ss` form (kernel-ba §"Time");
 * the field is filled (the catalog guards each extractor with `FieldFilled`). The sibling of
 * `DateExtractDiffTest`.
 */
class TimeExtractDiffTest {

    private val appt = RuntimeLaws.model("models/appointment.dm.json")
    private val fields = listOf(FieldDef(OT, Kind.TIME))
    private val kinds = mapOf(OT to Kind.TIME)

    private fun agree(cond: String, time: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(appt, "/Appointment", "R", "../OnSiteTime", "TX", cond), "TX",
            RuntimeLaws.doc().group("/Appointment", 1).field(OT, time, 1, 1))
        val i = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Appointment/R", "/Appointment", cond, "TX", "../OnSiteTime"))))
            .validate(Document(kinds).field(OT, time, 1, 1)).messages.any { it.code == "TX" }
        assertEquals(k, i, "$msg ($time)")
    }

    @Test fun hoursFromTime() {
        val c = "HoursFromTime(OnSiteTime) >= 12"
        agree(c, "14:30:50", "14:00+ is afternoon"); agree(c, "09:30:00", "09:00 is morning")
    }

    @Test fun minutesFromTime() {
        val c = "MinutesFromTime(OnSiteTime) == 30"
        agree(c, "14:30:50", "half past fires"); agree(c, "14:45:00", "quarter to no")
    }

    @Test fun secondsFromTime() {
        val c = "SecondsFromTime(OnSiteTime) < 10"
        agree(c, "14:30:05", "5s < 10 fires"); agree(c, "14:30:50", "50s not < 10")
    }

    private companion object {
        const val OT = "/Appointment/OnSiteTime"
    }
}
