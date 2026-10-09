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
 * Whether the checker refuses a temporal extractor's operand exactly where A12 does ([KERNEL-FINDINGS KF529],
 * `CheckDatumExtractOpImpl`). Every extractor is asked of every operand form the date slot takes, on the same
 * draft built with and without a Base Year: declared formats with and without the extracted component, a time
 * declared with a date's format, the three partial precisions, date, time and stamp constants, the keywords, and
 * the nested operations whose result format A12 registers. The rows are the kernel's own answer; the checker
 * must name the same class on each.
 *
 * Own domain (an insurance cover term).
 */
class CheckerTemporalExtractorAgreementTest {

    private fun term(baseYear: Int?): String = DocumentModelDraft.named("cover-term-extractors").locale("en_US")
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
            .dateRangeField("ContractYears") { r -> r.format("yyyy").separator("/") }
            .numberField("RenewalHour") { n -> n.fractionDigits(0).min("0").max("23") } }
        .build().toJson()

    private val operands = listOf(
        "StartOn", "Anniversary", "BillingMonth", "PolicyYear", "RenewalClock", "ClockAsDate", "SignedAt",
        "NotedDay", "NotedMonth", "NotedYear", "\"01.01.2024\"", "Time(RenewalHour)", "TimeFromDateTime(SignedAt)",
        "DateFromDateTime(SignedAt)", "\"01.01.2024T10:00:00\"", "\"10:30:00\"", "\"01.02.\"", "Today", "Now",
        "BaseYear", "AddDays(StartOn, 1)", "AddDays(Anniversary, 1)", "AddHours(SignedAt, 1)",
        "AddHours(RenewalClock, 1)", "DateTime(StartOn, RenewalClock)", "Date(\"01\", \"01\", \"2024\")",
        "Date(\"01\", \"01\")", "StartOfDateRange(CoverPeriod)", "EndOfDateRange(BaseYear)",
        "StartOfDateRange(RenewalWindow)", "EndOfDateRange(SeasonWindow)", "StartOfDateRange(ContractYears)",
    )

    /** Each extractor with a comparand of its result's kind. */
    private val extractors = linkedMapOf(
        "YearFromDate" to "== 2024", "MonthFromDate" to "== 1", "DayFromDate" to "== 1", "QuarterFromDate" to "== 1",
        "HoursFromTime" to "== 1", "MinutesFromTime" to "== 1", "SecondsFromTime" to "== 1",
        "DateFromDateTime" to "== [StartOn]", "TimeFromDateTime" to "== [RenewalClock]",
    )

    private val validator = RuleValidator()

    private fun draft(extractor: String, operand: String) = DraftRule("/Term", "ProbeRule", "../Holder",
        "FieldFilled(Holder) And $extractor($operand) ${extractors.getValue(extractor)}", "PROBE", "ERROR")

    private fun kernel(model: String, extractor: String, operand: String): Set<String> =
        validator.check(model, draft(extractor, operand)).let { r ->
            if (r.ok()) emptySet() else r.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }

    @Test
    fun theCheckerNamesA12sClassOnEveryExtractorOperand() {
        val wrong = mutableListOf<String>()
        for ((axis, model) in listOf("no base year" to term(null), "base year 2024" to term(2024))) {
            for (extractor in extractors.keys) {
                for (operand in operands) {
                    val expected = kernel(model, extractor, operand)
                    val ours = check(ModelSource(validator.inject(model, draft(extractor, operand)))).findings
                        .filter { it.justifiesRejection }.map { it.code }.toSet()
                    if (expected != ours) wrong += "$axis, $extractor($operand): A12 $expected, ours $ours"
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /**
     * The kernel's own answer on the rows that separate the gates (KF529), pinned so the agreement above cannot
     * follow a drift on both sides: a partial precision refuses before the format is asked, a clock declared with
     * a date's format extracts like a date, the date-yielding extractors take no Base Year exemption, and a nested
     * operation is read by the format A12 registers for it.
     */
    @Test
    fun aGateAnswersInA12sOrderOverTheFormatEachOperandCarries() {
        val wf = setOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        val rows = linkedMapOf(
            Triple(null, "HoursFromTime", "NotedDay") to setOf("MVK_INVALID_DATE_TYPE"),
            Triple(null, "YearFromDate", "NotedDay") to emptySet(),
            Triple(null, "QuarterFromDate", "NotedMonth") to setOf("MVK_INVALID_DATE_TYPE"),
            Triple(null, "YearFromDate", "\"01.01.2024\"") to setOf("MVK_NO_CONST"),
            Triple(null, "DateFromDateTime", "\"01.01.2024T10:00:00\"") to emptySet(),
            Triple(null, "YearFromDate", "ClockAsDate") to emptySet(),
            Triple(null, "HoursFromTime", "ClockAsDate") to wf,
            Triple(2024, "DateFromDateTime", "StartOn") to wf,
            Triple(null, "YearFromDate", "AddDays(Anniversary, 1)") to wf,
            Triple(2024, "HoursFromTime", "Date(\"01\", \"01\")") to wf,
            Triple(null, "YearFromDate", "StartOfDateRange(RenewalWindow)") to wf,
            Triple(null, "YearFromDate", "EndOfDateRange(SeasonWindow)") to emptySet(),
            Triple(null, "MonthFromDate", "StartOfDateRange(ContractYears)") to wf,
        )
        val observed = rows.keys.associateWith { (baseYear, extractor, operand) -> kernel(term(baseYear), extractor, operand) }
        assertEquals(rows, observed)
    }

    /**
     * The extractor's gate answers as soon as its call is read, ahead of a parse error later in the text: a bare
     * name is no comparand, and only the operand the gate admits reaches it (KF529).
     */
    @Test
    fun theGateAnswersBeforeALaterParseError() {
        fun codes(condition: String) = validator.check(term(null),
            DraftRule("/Term", "ProbeRule", "../Holder", "FieldFilled(Holder) And $condition", "PROBE", "ERROR"))
            .diagnostics().map { it.code().orElse("(no code)") }.toSet()
        assertEquals(
            listOf(setOf("MVK_WRONG_DATE_FORMAT_FOR_OP"), setOf("MVK_UNEXPECTED_TOKEN")),
            listOf(codes("DateFromDateTime(StartOn) == StartOn"), codes("DateFromDateTime(SignedAt) == StartOn")))
    }
}
