package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which operations a `Date(…)` or `Time(…)` component may be, the checker against A12. The kernel grammar's
 * `datumParam` is a bare entity, a date/time extract operation or a quoted constant, so every other operation in
 * a component is a parse error. Each component is asked in every slot of both constructions, the four-component
 * `Date`'s century and short year included; the admitted arms are the controls. The century takes a field bounded
 * anywhere from 10 to 99 where the short year needs 99, which `RenewalHour` (bounded 23) separates.
 *
 * Own domain (an insurance cover term).
 */
class CheckerConstructorComponentAgreementTest {

    private val model = DocumentModelDraft.named("cover-term-components").locale("en_US")
        .rootGroup("Term") { t -> t
            .stringField("Holder") { }
            .dateField("StartOn") { d -> d.format("yyyy-MM-dd") }
            .timeField("RenewalClock") { c -> c.format("HH:mm:ss") }
            .dateTimeField("SignedAt") { c -> c.format("yyyy-MM-dd'T'HH:mm:ss") }
            .numberField("RenewalHour") { n -> n.fractionDigits(0).min("0").max("23") }
            .numberField("Bounded9") { n -> n.fractionDigits(0).min("0").max("9") }
            .numberField("Bounded10") { n -> n.fractionDigits(0).min("0").max("10") }
            .numberField("Bounded99") { n -> n.fractionDigits(0).min("0").max("99") }
            .group("Claims") { g -> g.repeatable(5).indexField("Code")
                .stringField("Code") { }
                .numberField("HourNo") { n -> n.fractionDigits(0).min("0").max("23") } } }
        .build().toJson()

    private val constructions = listOf(
        "Date(%s, \"01\", \"2024\") > [StartOn]", "Date(\"01\", %s, \"2024\") > [StartOn]",
        "Date(\"01\", \"01\", %s) > [StartOn]", "Time(%s) > [RenewalClock]", "Time(\"10\", %s) > [RenewalClock]",
        "Time(\"10\", \"10\", %s) > [RenewalClock]", "Date(\"01\", \"01\", %s, \"24\") > [StartOn]",
        "Date(\"01\", \"01\", \"20\", %s) > [StartOn]",
    )

    private val components = listOf(
        // the admitted arms
        "RenewalHour", "\"01\"", "\"1\"", "Bounded9", "Bounded10", "Bounded99", "HoursFromTime(RenewalClock)", "Claims/HourNo For \"C1\"",
        // every other operation
        "MaxValue(Claims*/HourNo)", "Sum(Claims*/HourNo)", "[RenewalHour] + 1", "RenewalHour + 1",
        "1", "1.5", "-1", "True", "Today",
        "[RenewalHour]", "{ RenewalHour }", "Time(RenewalHour)", "Date(\"01\", \"01\", \"2024\")",
        "RoundUp(RenewalHour, 0)", "Claims*/HourNo", "Claims", "DateFromDateTime(SignedAt)", "TimeFromDateTime(SignedAt)",
    )

    private val validator = RuleValidator()

    @Test
    fun theCheckerNamesA12sClassOnEveryComponent() {
        val observed = linkedMapOf<String, String>()
        val wrong = mutableListOf<String>()
        for (construction in constructions) {
            for (component in components) {
                val condition = "FieldFilled(Holder) And " + construction.format(component)
                val draft = DraftRule("/Term", "ProbeRule", "../Holder", condition, "PROBE", "ERROR")
                val a12 = validator.check(model, draft).let { r ->
                    if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }.sorted()
                }
                val ours = check(ModelSource(validator.inject(model, draft))).findings
                    .filter { it.justifiesRejection }.map { it.code }.sorted()
                observed[construction.format(component)] = a12.joinToString()
                if (a12 != ours) wrong += "${construction.format(component)}: A12 $a12, ours $ours"
            }
        }
        assertEquals(emptyList<String>(), wrong)
        // A12's own answer on the rows that separate the arms, so the agreement cannot follow a drift on both sides.
        assertEquals(linkedMapOf(
            "Time(Claims/HourNo For \"C1\") > [RenewalClock]" to "",
            "Time(MaxValue(Claims*/HourNo)) > [RenewalClock]" to "MVK_UNEXPECTED_TOKEN",
            "Date(\"01\", Time(RenewalHour), \"2024\") > [StartOn]" to "MVK_UNEXPECTED_TOKEN",
            "Date(\"01\", \"01\", [RenewalHour] + 1) > [StartOn]" to "MVK_UNEXPECTED_TOKEN",
            "Time(1) > [RenewalClock]" to "MVK_INVALID_ENTITY",
            "Time(Claims*/HourNo) > [RenewalClock]" to "MVK_NO_WILDCARDS_ALLOWED",
            "Date(\"01\", DateFromDateTime(SignedAt), \"2024\") > [StartOn]" to "MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED",
            "Time(\"10\", HoursFromTime(RenewalClock)) > [RenewalClock]" to "MVK_INVALID_DATE_OR_TIME_EXTRACT_OP",
            "Time(\"10\", \"10\", 1.5) > [RenewalClock]" to "MVK_UNEXPECTED_TOKEN",
            "Time(True) > [RenewalClock]" to "MVK_UNEXPECTED_TOKEN",
            "Time(Today) > [RenewalClock]" to "MVK_UNEXPECTED_TOKEN",
            "Date(\"01\", \"01\", DateFromDateTime(SignedAt), \"24\") > [StartOn]" to
                "MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS",
            "Date(\"01\", \"01\", RenewalHour, \"24\") > [StartOn]" to "",
            "Date(\"01\", \"01\", \"20\", RenewalHour) > [StartOn]" to "MVK_WRONG_DATE_FORMAT_FOR_OP",
            "Date(\"01\", \"01\", \"01\", \"24\") > [StartOn]" to "MVK_WRONG_DATE_FORMAT_FOR_OP",
            "Date(\"01\", \"01\", Bounded10, \"24\") > [StartOn]" to "",
            "Date(\"01\", \"01\", Bounded9, \"24\") > [StartOn]" to "MVK_WRONG_DATE_FORMAT_FOR_OP",
            "Date(\"01\", \"01\", \"20\", Bounded99) > [StartOn]" to "",
            "Date(\"01\", \"01\", \"20\", \"1\") > [StartOn]" to "MVK_WRONG_DATE_FORMAT_FOR_OP",
        ), observed.filterKeys { it in PINNED })
    }

    /**
     * Two violations in one construction, in both text orders: whether a parse-class component or a misplaced
     * extract operation answers first, and whether `Date` and `Time` order them alike.
     */
    @Test
    fun twoViolationsInOneConstruction() {
        val rows = listOf(
            "Date(DateFromDateTime(SignedAt), \"01\", Sum(Claims*/HourNo)) > [StartOn]",
            "Date(Sum(Claims*/HourNo), \"01\", DateFromDateTime(SignedAt)) > [StartOn]",
            "Date(MonthFromDate(StartOn), \"01\", Sum(Claims*/HourNo)) > [StartOn]",
            "Date(Sum(Claims*/HourNo), MonthFromDate(StartOn), \"2024\") > [StartOn]",
            "Time(MinutesFromTime(RenewalClock), Sum(Claims*/HourNo)) > [RenewalClock]",
            "Time(Sum(Claims*/HourNo), MinutesFromTime(RenewalClock)) > [RenewalClock]",
            "Time(DateFromDateTime(SignedAt), Sum(Claims*/HourNo)) > [RenewalClock]",
            "Time(Sum(Claims*/HourNo), TimeFromDateTime(SignedAt)) > [RenewalClock]",
            "Time(1, Sum(Claims*/HourNo)) > [RenewalClock]",
            "Time(Sum(Claims*/HourNo), 1) > [RenewalClock]",
        )
        val observed = linkedMapOf<String, String>()
        val wrong = mutableListOf<String>()
        for (row in rows) {
            val draft = DraftRule("/Term", "ProbeRule", "../Holder", "FieldFilled(Holder) And " + row, "PROBE", "ERROR")
            val a12 = validator.check(model, draft).let { r ->
                if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }.sorted()
            }
            val ours = check(ModelSource(validator.inject(model, draft))).findings
                .filter { it.justifiesRejection }.map { it.code }.sorted()
            observed[row] = a12.joinToString()
            if (a12 != ours) wrong += "$row: A12 $a12, ours $ours"
        }
        assertEquals(emptyList<String>(), wrong)
        // a parse error answers before any extractor's slot gate, and the first written of two parse errors does
        val token = "MVK_UNEXPECTED_TOKEN"
        assertEquals(rows.associateWith { if (it.startsWith("Time(1,")) "MVK_INVALID_ENTITY" else token }, observed)
    }

    private companion object {
        val PINNED = setOf(
            "Time(Claims/HourNo For \"C1\") > [RenewalClock]", "Time(MaxValue(Claims*/HourNo)) > [RenewalClock]",
            "Date(\"01\", Time(RenewalHour), \"2024\") > [StartOn]", "Date(\"01\", \"01\", [RenewalHour] + 1) > [StartOn]",
            "Time(1) > [RenewalClock]", "Time(Claims*/HourNo) > [RenewalClock]",
            "Date(\"01\", DateFromDateTime(SignedAt), \"2024\") > [StartOn]",
            "Time(\"10\", HoursFromTime(RenewalClock)) > [RenewalClock]",
            "Time(\"10\", \"10\", 1.5) > [RenewalClock]",
            "Time(True) > [RenewalClock]", "Time(Today) > [RenewalClock]",
            "Date(\"01\", \"01\", DateFromDateTime(SignedAt), \"24\") > [StartOn]",
            "Date(\"01\", \"01\", RenewalHour, \"24\") > [StartOn]",
            "Date(\"01\", \"01\", \"20\", RenewalHour) > [StartOn]",
            "Date(\"01\", \"01\", \"01\", \"24\") > [StartOn]",
            "Date(\"01\", \"01\", Bounded10, \"24\") > [StartOn]", "Date(\"01\", \"01\", Bounded9, \"24\") > [StartOn]",
            "Date(\"01\", \"01\", \"20\", Bounded99) > [StartOn]", "Date(\"01\", \"01\", \"20\", \"1\") > [StartOn]",
        )
    }
}
