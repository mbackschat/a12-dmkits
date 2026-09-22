package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **model-zone calendar-day arithmetic over DATE_TIME operands** (KERNEL-FINDINGS §6):
 *
 *  - `DifferenceInDays` is **time-of-day aware** and counts complete stateful legacy-calendar steps, not
 *    elapsed/wall-label seconds divided by 86,400. `23:00 → 01:00` next day is 0; a mixed
 *    `(datetime, date)` pair is legal, the date reading as model-zone midnight.
 *  - `AddDays` over a datetime is legal and **time-preserving** (`15T22:00 + 1 day = 16T22:00`,
 *    [addDaysOverADateTimeIsTimePreserving]) — the kernel's zone-calendar `add(DAY)`; the interpreter
 *    previously resolved it to Unknown (a silent suppression divergence).
 *  - `DifferenceIn{Months,Years}` and `Add{Months,Years}` REJECT datetime operands at model validation
 *    (`MVK_WRONG_DATE_FORMAT_FOR_OP`), so their date-part semantics stay unreachable-safe (no lock needed).
 *
 * The typed DSL currently models `addDays` as date-valued (`DateExpr`), so the time-preserving
 * datetime-comparison cases are authored as raw canonical conditions (DEV-API-GAPS: typed datetime add).
 */
class WallDayArithmeticDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(CA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(DD, Kind.DATE),
    )
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private fun agree(
        cond: String,
        sa: String,
        ca: String?,
        dd: String?,
        msg: String,
        expected: Boolean? = null,
    ) {
        var kDoc = RuntimeLaws.doc().group("/Order", 1).field(SA, sa, 1, 1)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "TA", "../ScheduledAt")),
            timeZone = ModelTimeZone.EUROPE_BERLIN)
        var iDoc = def.newDocument().field(SA, sa, 1, 1)
        if (ca != null) { kDoc = kDoc.field(CA, ca, 1, 1); iDoc = iDoc.field(CA, ca, 1, 1) }
        if (dd != null) { kDoc = kDoc.field(DD, dd, 1, 1); iDoc = iDoc.field(DD, dd, 1, 1) }
        val kernelModel = RuntimeLaws.withRule(order, "/Order", "R", "../ScheduledAt", "TA", cond)
        val k = RuntimeLaws.fires(kernelModel, "TA", kDoc)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "TA" }
        assertEquals(k, i, "$msg (sa=$sa ca=$ca dd=$dd) [k=$k i=$i]")
        if (expected != null) {
            val java = RuntimeLaws.kernelJavaEngine(kernelModel)
            val generated = java.messages(java.instance(kDoc)).any { it.code() == "TA" }
            assertEquals(k, generated, "$msg — generated Java (dynamic=$k generated=$generated)")
            assertEquals(expected, k, "$msg — expected kernel firing")
        }
    }

    @Test fun diffDaysIsTimeOfDayAware() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInDays(m.dateTime(SA), m.dateTime(CA)).eq(0))),
        "2024-06-15T23:00:00", "2024-06-16T01:00:00", null, "2 wall hours into the next date = 0 whole days")

    @Test fun diffDaysReverseTruncatesTowardZero() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInDays(m.dateTime(SA), m.dateTime(CA)).eq(0))),
        "2024-06-16T01:00:00", "2024-06-15T23:00:00", null, "reverse −2 wall hours = 0, not −1")

    @Test fun diffDaysCountsCalendarStepsAcrossTheSpringTransition() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInDays(m.dateTime(SA), m.dateTime(CA)).eq(1))),
        "2024-03-30T12:00:00", "2024-03-31T12:00:00", null, "one model-zone calendar step reaches the endpoint")

    @Test fun diffDaysGapSeriesUsesStatefulCalendarLandings() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInDays(m.dateTime(SA), m.dateTime(CA)).eq(2))),
        "2024-03-29T02:30:00", "2024-03-31T03:00:00", null, "two stateful calendar landings fit (probe P15)")

    @Test fun mixedOperandsAreLegal() = agree(
        render(m.dateTime(SA).filled().and(Dsl.differenceInDays(m.dateTime(SA), m.date(DD)).eq(0))),
        "2024-06-15T23:00:00", null, "2024-06-16", "the DATE reads as midnight: 1 wall hour = 0 whole days")

    @Test fun addDaysOverADateTimeIsTimePreserving() = agree(
        "AddDays(ScheduledAt, 1) == [CompletedAt]",
        "2024-06-15T22:00:00", "2024-06-16T22:00:00", null, "AddDays keeps the clock time (probe P16)")

    @Test fun addDaysLandingInTheGapUsesTheEarlierNoOvershootInstant() = agree(
        "AddDays(ScheduledAt, 1) == [CompletedAt]",
        "2024-03-30T02:30:00", "2024-03-31T01:30:00", null,
        "a forward landing on nonexistent 02:30 selects the earlier 01:30 instant", expected = true)

    private companion object {
        const val SA = "/Order/ScheduledAt"
        const val CA = "/Order/CompletedAt"
        const val DD = "/Order/DeliveryDate"
    }
}
