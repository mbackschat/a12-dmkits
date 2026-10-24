package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The `DateRange` construct, one-kind value lists, and a categorized product operand, on the shapes
 * a12-kernel-lean's K22 S2 checkpoint reached (SPEC-2026-10-24-07, KERNEL-FINDINGS KF613).
 *
 * The construct takes two field specs (`datumBereichKonstrukt`), not the date slot the difference and shift
 * operators share, and yields a range of its first date's components. A value list is one kind throughout
 * (`wertListenSpezifier`, `wertListenSpezifier2`). A product sum keys a categorized operand as its field.
 *
 * [ROWS] pins A12's answer on every row, and the checker must name exactly the same classes. Own domain: an
 * insurance policy with a cover period and repeatable claims.
 */
class CheckerRangeConstructAgreementTest {

    private val policy = DocumentModelDraft.named("policy-range-construct").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Title") { }
            .stringField("Holder") { }
            .numberField("Amount") { n -> n.fractionDigits(0) }
            .numberField("Total") { n -> n.fractionDigits(0) }
            .dateField("StartsOn") { d -> d.format("yyyy-MM-dd") }
            .dateField("EndsOn") { d -> d.format("dd.MM.yyyy") }
            .dateField("PolicyYear") { d -> d.format("yyyy") }
            .dateField("Noted") { d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.YEAR_OPTIONAL) }
            .timeField("Clock") { t -> t.format("HH:mm:ss") }
            .dateTimeField("Stamp") { s -> s.format("yyyy-MM-dd'T'HH:mm:ss") }
            .dateRangeField("Cover") { r -> r.format("yyyy-MM-dd").separator("/") }
            .dateRangeField("Cover2") { r -> r.format("yyyy-MM-dd").separator("/") }
            .dateRangeField("Season") { r -> r.format("yyyy").separator("/") }
            .dateFragmentField("Renewal") { f -> f.fragmentFormat("MM-dd") }
            .enumField("Tier") { e -> e.value("A").value("B").category("Band", "Hi", "Lo") }
            .group("Agent") { a -> a.stringField("AgentName") { } }
            .group("Claims") { c -> c.repeatable(9)
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .dateField("Filed") { d -> d.format("yyyy-MM-dd") }
                .enumField("Level") { e -> e.value("A").value("B").category("Band", "Hi", "Lo") } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS, ROWS.mapValues { judge(it.key).first })
    }

    @Test
    fun theCheckerNamesWhatA12Names() {
        assertEquals(ROWS.mapValues { judge(it.key).first }, ROWS.mapValues { judge(it.key).second })
    }

    private fun judge(condition: String): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Policy", "RangeProbe", "../Title", "FieldFilled(Title) And $condition", "PROBE", "ERROR")
        val result = validator.check(policy, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(validator.inject(policy, draft))).findings.filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    private companion object {
        const val UNEXPECTED = "MVK_UNEXPECTED_TOKEN"
        const val WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP"
        const val TO_RANGE = "MVK_INVALID_COMPARE_TO_DATE_RANGE"
        const val RANGE = "\"2020-01-01/2020-02-01\""

        val ROWS: Map<String, List<String>> = linkedMapOf(
            "DateRange(\"2020-01-01\", StartsOn) == [Cover]" to listOf(UNEXPECTED),
            "DateRange(StartsOn, \"01.01.2020\") == [Cover]" to listOf(UNEXPECTED),
            "DateRange(Date(\"01\", \"01\", \"2020\"), EndsOn) == [Cover]" to listOf(UNEXPECTED),
            "DateRange(StartsOn, EndsOn) == [Cover]" to emptyList(),
            "DateRange(StartsOn, EndsOn) == $RANGE" to listOf(TO_RANGE),
            "$RANGE == DateRange(StartsOn, EndsOn)" to listOf(TO_RANGE),
            "DateRange(StartsOn, EndsOn) == [Season]" to listOf(TO_RANGE),
            "DateRange(PolicyYear, PolicyYear) == [Season]" to emptyList(),
            "DateRange(StartsOn, EndsOn) == DateRange(EndsOn, StartsOn)" to emptyList(),
            "DateRange(StartsOn, EndsOn) == [StartsOn]" to listOf("MVK_INVALID_COMPARE_TO_DATE"),
            "DateRange(StartsOn, EndsOn) == [Amount]" to listOf(TO_RANGE),
            "DateRange(StartsOn, Title) == [Cover]" to listOf(WRONG_FORMAT),
            "DateRange(Clock, StartsOn) == [Cover]" to listOf(WRONG_FORMAT),
            "DateRange(StartsOn, Stamp) == [Cover]" to listOf(WRONG_FORMAT),
            "DateRange(StartsOn, Noted) == [Cover]" to listOf(WRONG_FORMAT),
            "DateRange(StartsOn, Cover) == [Cover]" to listOf(WRONG_FORMAT),
            "DateRange(StartsOn, PolicyYear) == [Cover]" to listOf(WRONG_FORMAT),
            // A repeatable group is the reference reader's to refuse first, unstarred as it is.
            "DateRange(Claims, StartsOn) == [Cover]" to listOf("MVK_NO_WILDCARD"),
            "DateRange(Agent, StartsOn) == [Cover]" to listOf("MVK_NO_GROUPS_ALLOWED"),
            "DateRange(StartsOn, Claims*/Filed) == [Cover]" to listOf("MVK_NO_WILDCARDS_ALLOWED"),
            "DateRange(Renewal, Renewal) == $RANGE" to listOf(TO_RANGE),
            "AtLeastOneDateRangeOverlaps(Cover In Cover2, $RANGE)" to listOf(UNEXPECTED),
            "AtLeastOneDateRangeOverlaps(Cover In Cover2)" to emptyList(),
            "FieldValueIncludedInValueList(Amount, 1, \"a\")" to listOf(UNEXPECTED),
            "FieldValueIncludedInValueList(Amount, 1, [Total])" to listOf(UNEXPECTED),
            "FieldValueIncludedInValueList(Amount, [Total], 1)" to listOf(UNEXPECTED),
            "FieldValueIncludedInValueList(Title, [Holder], \"a\")" to listOf(UNEXPECTED),
            "FieldValueIncludedInValueList(Title, \"a\", [Holder])" to listOf(UNEXPECTED),
            "FieldValueIncludedInValueList(Amount, 1, -2)" to emptyList(),
            "FieldValueIncludedInValueList(Title, [Holder])" to emptyList(),
            "SumOfProducts(Tier -> Band, Total) > 0" to listOf("MVK_REPEATABLE_LEVEL_REQUIRED"),
            "SumOfProducts(Amount, Tier -> Band) > 0" to listOf("MVK_REPEATABLE_LEVEL_REQUIRED"),
            "SumOfProducts(Tier -> Band, Tier) > 0" to listOf("MVK_REPEATABLE_LEVEL_REQUIRED"),
            "SumOfProducts(Claims*/Level -> Band, Claims*/Qty) > 0" to listOf("MVK_NO_NUMBER"),
        )
    }
}
