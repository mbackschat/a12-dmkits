package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether the checker refuses a `DateTime(date, time)` operand exactly where A12 does ([KERNEL-FINDINGS KF627],
 * `CheckDateTimeKonstruktImpl`, a12-kernel-lean SPEC-2026-10-26-21). Every operand form a temporal slot takes is
 * placed once in the Date slot beside a valid time and once in the Time slot beside a valid date, on the same
 * draft built with and without a Base Year. The rows are the kernel's own answer; the checker must name the same
 * class on each.
 *
 * Own domain (an insurance cover term).
 */
class CheckerDateTimeConstructionAgreementTest {

    private fun term(baseYear: Int?): String = DocumentModelDraft.named("cover-term-stamps").locale("en_US")
        .let { if (baseYear == null) it else it.baseYear(baseYear) }
        .rootGroup("Term") { t -> t
            .stringField("Holder") { }
            .dateField("StartOn") { d -> d.format("yyyy-MM-dd") }
            .dateField("Anniversary") { d -> d.format("MM-dd") }
            .dateField("BillingMonth") { d -> d.format("yyyy-MM") }
            .dateField("PolicyYear") { d -> d.format("yyyy") }
            .timeField("RenewalClock") { c -> c.format("HH:mm:ss") }
            .timeField("ClockAsDate") { c -> c.format("yyyy-MM-dd") }
            .dateTimeField("SignedAt") { s -> s.format("yyyy-MM-dd'T'HH:mm:ss") }
            .dateField("NotedDay") { d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) }
            .dateField("NotedMonth") { d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL) }
            .dateField("NotedYear") { d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.YEAR_OPTIONAL) }
            .dateRangeField("CoverPeriod") { r -> r.format("yyyy-MM-dd").separator("/") }
            .dateRangeField("RenewalWindow") { r -> r.format("MM-dd").separator("/") }
            .dateRangeField("SeasonWindow") { r -> r.format("MM-dd").separator("/").interpretationOfYear(RangeYearInterpretation.FROM) }
            .numberField("RenewalHour") { n -> n.fractionDigits(0).min("0").max("23") }
            .group("Claims") { g -> g.repeatable(5).indexField("Code")
                .stringField("Code") { }
                .dateField("FiledOn") { d -> d.format("yyyy-MM-dd") }
                .dateField("NotedOn") { d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) }
                .timeField("FiledAt") { c -> c.format("HH:mm:ss") } } }
        .build().toJson()

    private val operands = listOf(
        "StartOn", "Anniversary", "BillingMonth", "PolicyYear", "RenewalClock", "ClockAsDate", "SignedAt",
        "NotedDay", "NotedMonth", "NotedYear", "\"01.01.2024\"", "\"01.02.\"", "\"10:30:00\"", "\"01.01.2024T10:00:00\"",
        "Today", "Now", "BaseYear", "Time(RenewalHour)", "Time(\"10\")", "TimeFromDateTime(SignedAt)",
        "DateFromDateTime(SignedAt)", "AddDays(StartOn, 1)", "AddDays(Anniversary, 1)", "AddHours(SignedAt, 1)",
        "AddHours(RenewalClock, 1)", "DateTime(StartOn, RenewalClock)", "Date(\"01\", \"01\", \"2024\")",
        "Date(\"01\", \"01\")", "StartOfDateRange(CoverPeriod)", "StartOfDateRange(RenewalWindow)",
        "EndOfDateRange(SeasonWindow)", "Claims/FiledOn For \"C1\"", "Claims/NotedOn For \"C1\"",
        "Claims/FiledAt For \"C1\"",
    )

    /** Each slot, with the valid partner the other one holds. */
    private val slots = linkedMapOf(
        "date" to { operand: String -> "DateTime($operand, RenewalClock)" },
        "time" to { operand: String -> "DateTime(StartOn, $operand)" },
    )

    private val validator = RuleValidator()

    private fun draft(construction: String) = DraftRule("/Term", "ProbeRule", "../Holder",
        "FieldFilled(Holder) And $construction == [SignedAt]", "PROBE", "ERROR")

    private fun kernel(model: String, construction: String): List<String> =
        validator.check(model, draft(construction)).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.sorted()
        }

    private fun ours(model: String, construction: String): List<String> =
        check(ModelSource(validator.inject(model, draft(construction)))).findings
            .filter { it.justifiesRejection }.map { it.code }.sorted()

    @Test
    fun theCheckerNamesA12sClassOnEveryOperandOfEitherSlot() {
        val wrong = mutableListOf<String>()
        for ((axis, model) in listOf("no base year" to term(null), "base year 2024" to term(2024))) {
            for ((slot, construct) in slots) {
                for (operand in operands) {
                    val construction = construct(operand)
                    val expected = kernel(model, construction)
                    val actual = ours(model, construction)
                    if (expected != actual) wrong += "$axis, $slot: $construction: A12 $expected, ours $actual"
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /**
     * A12's own answer on the rows that separate the gates, pinned so the agreement above cannot follow a drift
     * on both sides: the Date slot is asked before the Time slot, a partial precision is refused only once the
     * format passes, a Base Year supplies only the year, and the Time slot refuses any date component.
     */
    @Test
    fun aSlotAnswersInA12sOrder() {
        val wf = listOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        val partial = listOf("MVK_INVALID_DATE_TYPE")
        val rows = linkedMapOf(
            Pair(null, "DateTime(StartOn, RenewalClock)") to emptyList(),
            Pair(null, "DateTime(NotedMonth, RenewalClock)") to partial,
            Pair(null, "DateTime(NotedMonth, StartOn)") to partial,
            Pair(null, "DateTime(StartOn, StartOn)") to wf,
            Pair(null, "DateTime(Anniversary, RenewalClock)") to wf,
            Pair(2024, "DateTime(Anniversary, RenewalClock)") to emptyList(),
            Pair(2024, "DateTime(RenewalClock, RenewalClock)") to wf,
            Pair(null, "DateTime(BillingMonth, RenewalClock)") to emptyList(),
            Pair(null, "DateTime(SignedAt, RenewalClock)") to wf,
            Pair(null, "DateTime(StartOn, SignedAt)") to wf,
            Pair(null, "DateTime(StartOn, ClockAsDate)") to wf,
            Pair(null, "DateTime(Claims/NotedOn For \"C1\", RenewalClock)") to partial,
        )
        val observed = rows.keys.associateWith { (baseYear, construction) -> kernel(term(baseYear), construction) }
        assertEquals(rows, observed)
    }
}
