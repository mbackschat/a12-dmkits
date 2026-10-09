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
 * a component is a parse error. Each component is asked in every slot of both constructions; the admitted arms
 * are the controls.
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
            .group("Claims") { g -> g.repeatable(5).indexField("Code")
                .stringField("Code") { }
                .numberField("HourNo") { n -> n.fractionDigits(0).min("0").max("23") } } }
        .build().toJson()

    private val constructions = listOf(
        "Date(%s, \"01\", \"2024\") > [StartOn]", "Date(\"01\", %s, \"2024\") > [StartOn]",
        "Date(\"01\", \"01\", %s) > [StartOn]", "Time(%s) > [RenewalClock]", "Time(\"10\", %s) > [RenewalClock]",
    )

    private val components = listOf(
        // the admitted arms
        "RenewalHour", "\"01\"", "HoursFromTime(RenewalClock)", "Claims/HourNo For \"C1\"",
        // every other operation
        "MaxValue(Claims*/HourNo)", "Sum(Claims*/HourNo)", "[RenewalHour] + 1", "RenewalHour + 1", "1",
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
        ), observed.filterKeys { it in PINNED })
    }

    private companion object {
        val PINNED = setOf(
            "Time(Claims/HourNo For \"C1\") > [RenewalClock]", "Time(MaxValue(Claims*/HourNo)) > [RenewalClock]",
            "Date(\"01\", Time(RenewalHour), \"2024\") > [StartOn]", "Date(\"01\", \"01\", [RenewalHour] + 1) > [StartOn]",
            "Time(1) > [RenewalClock]", "Time(Claims*/HourNo) > [RenewalClock]",
            "Date(\"01\", DateFromDateTime(SignedAt), \"2024\") > [StartOn]",
            "Time(\"10\", HoursFromTime(RenewalClock)) > [RenewalClock]",
        )
    }
}
