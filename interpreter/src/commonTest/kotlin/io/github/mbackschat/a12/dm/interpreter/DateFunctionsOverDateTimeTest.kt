package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF95 — the date-component extractors (`DayFromDate`/`MonthFromDate`/
 * `YearFromDate`/`QuarterFromDate`) and the date diffs accept a **DATE_TIME operand** and use its DATE part
 * (the kernel parses a datetime with the field's time-bearing format — there is no datetime-specific format
 * definition). Kernel-verified by `adapter.laws.DateFunctionsOverDateTimeDiffTest`.
 */
class DateFunctionsOverDateTimeTest {

    private val LOGGED = "/Ops/Shipment/LoggedAt"
    private val DELIVERED = "/Ops/Shipment/DeliveredAt"
    private val OUT = "/Ops/Shipment/Extracted"

    private fun model(op: String) = ModelDef(
        fields = listOf(
            FieldDef(LOGGED, Kind.DATE_TIME),
            FieldDef(DELIVERED, Kind.DATE_TIME),
            FieldDef(OUT, Kind.NUMBER, scale = 0),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(OUT, "/Ops/Shipment", op)),
    )

    private fun outcome(op: String, logged: String?, delivered: String? = null): Pair<ComputeOutcome?, String?> {
        val m = model(op)
        val d = m.newDocument().group("/Ops", 1).group("/Ops/Shipment", 1, 1).field(OUT, "-7", 1, 1, 1)
        logged?.let { d.field(LOGGED, it, 1, 1, 1) }
        delivered?.let { d.field(DELIVERED, it, 1, 1, 1) }
        val c = DmInterpreter(m).compute(d).firstOrNull { it.path == OUT }
        return c?.outcome to c?.value
    }

    private val TS = "2024-06-25T05:21:07"

    @Test fun extractorsUseTheDatePart() {
        assertEquals(ComputeOutcome.VALUE to "25", outcome("DayFromDate(LoggedAt)", TS))
        assertEquals(ComputeOutcome.VALUE to "6", outcome("MonthFromDate(LoggedAt)", TS))
        assertEquals(ComputeOutcome.VALUE to "2024", outcome("YearFromDate(LoggedAt)", TS))
        assertEquals(ComputeOutcome.VALUE to "2", outcome("QuarterFromDate(LoggedAt)", TS))
    }

    @Test fun emptyDateTimeExtractsTheFillableZero() =
        assertEquals(ComputeOutcome.VALUE to "0", outcome("DayFromDate(LoggedAt)", null))

    @Test fun differenceInDaysUsesTheDateParts() =
        assertEquals(ComputeOutcome.VALUE to "2",
            outcome("DifferenceInDays(LoggedAt, DeliveredAt)", TS, "2024-06-27T23:59:59"),
            "the times are ignored — whole-day date-part difference")
}
