package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for SPEC-2026-07-27-02 — `ValueAsDate(field, FirstDay|LastDay)` resolves in the
 * three NESTED positions the kernel grammar's `datumAngabe2` admits: a `Add*` date base, either
 * `DifferenceIn*` operand, and the DATE argument of `DateTime(date, time)`. Placement legality is
 * kernel-locked in `rulekit.validate.laws.ValueAsDatePlacementLawsTest`; the dual-kernel-route runtime
 * agreement is `adapter.laws.ValueAsDateDiffTest`. This is the Node half of that evidence: the resolved
 * partial date must flow through each nesting consumer identically on both hosts.
 *
 * The fixture is one day-omitted June value (`00.06.2024`), so FirstDay is the 1st and LastDay the 30th — a
 * 29-step span. Each case pins the exact stored value, so a consumer that silently dropped the resolution
 * (or resolved to the wrong boundary) fails rather than merely agreeing with itself.
 */
class ValueAsDateNestedPlacementTest {

    private fun model(target: String, kind: Kind, format: String?, operation: String) = ModelDef(
        fields = listOf(
            FieldDef(APPROX, Kind.DATE, format = "dd.MM.yyyy", datePrecision = DatePrecision.DAY_OPTIONAL),
            FieldDef(PICKUP, Kind.TIME, format = "HH:mm:ss"),
            FieldDef(target, kind, format = format),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(target, "/Claim", operation)),
    )

    private fun computed(target: String, kind: Kind, format: String?, operation: String): String? {
        val m = model(target, kind, format, operation)
        val d = m.newDocument().group("/Claim", 1)
            .field(APPROX, "00.06.2024", 1, 1)
            .field(PICKUP, "09:30:00", 1, 1)
        val c = DmInterpreter(m).compute(d).firstOrNull { it.path == target }
        return if (c?.outcome == ComputeOutcome.VALUE) c.value else null
    }

    private fun date(operation: String) = computed(LANDED, Kind.DATE, "dd.MM.yyyy", operation)

    @Test fun theShiftBaseResolvesFirstDay() =
        assertEquals("02.06.2024", date("AddDays(ValueAsDate(/Claim/Approx, FirstDay), 1)"),
            "FirstDay of a day-omitted June is the 1st, +1 day the 2nd")

    @Test fun theShiftBaseResolvesLastDayAndCrossesTheMonth() =
        assertEquals("01.07.2024", date("AddDays(ValueAsDate(/Claim/Approx, LastDay), 1)"),
            "LastDay of June is the 30th, +1 day crosses into July")

    @Test fun theShiftBaseAcceptsTheMonthUnitToo() =
        assertEquals("01.07.2024", date("AddMonths(ValueAsDate(/Claim/Approx, FirstDay), 1)"),
            "the same operand resolves under AddMonths")

    @Test fun bothDifferenceOperandsResolve() =
        assertEquals("29", computed(SPAN, Kind.NUMBER, null,
            "DifferenceInDays(ValueAsDate(/Claim/Approx, FirstDay), ValueAsDate(/Claim/Approx, LastDay))"),
            "first to last day of June is 29 calendar steps — both operands resolved")

    @Test fun theDifferenceIsDirectional() =
        assertEquals("-29", computed(SPAN, Kind.NUMBER, null,
            "DifferenceInDays(ValueAsDate(/Claim/Approx, LastDay), ValueAsDate(/Claim/Approx, FirstDay))"),
            "the reverse order negates, so neither operand is silently canonicalized")

    @Test fun theDateArgumentOfDateTimeResolves() =
        assertEquals("2024-06-01T09:30:00", computed(STAMP, Kind.DATE_TIME, "yyyy-MM-dd'T'HH:mm:ss",
            "DateTime(ValueAsDate(/Claim/Approx, FirstDay), /Claim/Pickup)"),
            "the resolved FirstDay composes with the time field")

    @Test fun theDateArgumentOfDateTimeSeparatesTheBoundary() =
        assertEquals("2024-06-30T09:30:00", computed(STAMP, Kind.DATE_TIME, "yyyy-MM-dd'T'HH:mm:ss",
            "DateTime(ValueAsDate(/Claim/Approx, LastDay), /Claim/Pickup)"),
            "LastDay composes to the 30th, not the 1st")

    private companion object {
        const val APPROX = "/Claim/Approx"
        const val PICKUP = "/Claim/Pickup"
        const val LANDED = "/Claim/Landed"
        const val SPAN = "/Claim/Span"
        const val STAMP = "/Claim/Stamp"
    }
}
