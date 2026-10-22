package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `$`-marked operand inside a `Having` body, on every operand family that reads a call operand: A12 judges the
 * marked reference with the gates an unmarked one meets, addressed by the enclosing rule's own iteration
 * (CONSISTENCY-GAPS DG147, KERNEL-FINDINGS KF599).
 *
 * Each body is measured twice. [ALONE] makes it the whole filter, so a sound marked operand meets the filter-end
 * gate (`MVK_NO_ITERATION_FOR_WILDCARD`, since nothing unmarked reopens the starred level) and a defective one
 * must answer first. [BESIDE] adds an unmarked read of that level, so a sound operand is admitted. The unmarked
 * group rows separate the two `Range` and `ValueAsDate` arms that once stood down on a repeatable group, and the
 * rule on `BirthYear` iterates nothing, so a marked or unmarked `Items` is the reference reader's there.
 *
 * Own domain: a shop customer and the items of an order.
 */
class CheckerOuterMarkedOperandAgreementTest {

    private val customer = DocumentModelDraft.named("customer-marked-items").locale("en_US")
        .rootGroup("Customer") { c -> c
            .numberField("BirthMonth") { n -> n.fractionDigits(0).min("1").max("12") }
            .numberField("BirthYear") { n -> n.fractionDigits(0).min("1800").max("2200") }
            .group("Items") { i -> i.repeatable(20)
                .numberField("Count") { n -> n.fractionDigits(0) }
                .numberField("Day") { n -> n.fractionDigits(0).min("1").max("31") }
                .numberField("UnitPrice") { n -> n.fractionDigits(2) }
                .stringField("Code") { }
                .dateField("ShipOn") { d -> d.format("yyyy-MM-dd") } } }
        .build().toJson()

    private val validator = RuleValidator()

    private fun judge(row: Row): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Customer", "MarkedProbe", "../${row.errorField}", row.condition, "PROBE", "ERROR")
        val a12 = validator.check(customer, draft).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
        }
        val ours = check(ModelSource(validator.inject(customer, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.a12 }, ROWS.mapValues { judge(it.value).first })
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        val verdicts = ROWS.mapValues { judge(it.value) }
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private class Row(val errorField: String, val condition: String, val a12: List<String>)

    private companion object {
        const val NO_GROUPS = "MVK_NO_GROUPS_ALLOWED"
        const val NO_WILDCARDS = "MVK_NO_WILDCARDS_ALLOWED"
        const val NO_WILDCARD = "MVK_NO_WILDCARD"
        const val FILTER_END = "MVK_NO_ITERATION_FOR_WILDCARD"

        /** The body as the whole filter: nothing unmarked reopens the starred level. */
        const val ALONE = "FieldFilled(Items/Count) And AtLeastOneFieldFilled(Items*/Count Having %s)"

        /** The same body beside an unmarked read of the starred level, which the filter-end gate asks for. */
        const val BESIDE = "FieldFilled(Items/Count) And AtLeastOneFieldFilled(Items*/Count Having [Items/Count] > 0 And %s)"

        val ROWS: Map<String, Row> = LinkedHashMap<String, Row>().apply {
            /** A defect the family names in both carriers. */
            fun defect(body: String, vararg a12: String) {
                put("$body, alone", Row("Items/Count", ALONE.format(body), a12.toList()))
                put("$body, beside", Row("Items/Count", BESIDE.format(body), a12.toList()))
            }
            /** A sound operand: the filter-end gate alone, admitted beside an unmarked read. */
            fun sound(body: String) {
                put("$body, alone", Row("Items/Count", ALONE.format(body), listOf(FILTER_END)))
                put("$body, beside", Row("Items/Count", BESIDE.format(body), emptyList()))
            }
            fun outside(errorField: String, condition: String, vararg a12: String) =
                put("$condition, on $errorField", Row(errorField, condition, a12.toList()))

            defect("RoundUpValue(\$Items, 2) > 0", NO_GROUPS)
            defect("RoundUpValue(\$Items*/UnitPrice, 2) > 0", NO_WILDCARDS)
            sound("RoundUpValue(\$Items/UnitPrice, 2) > 0")
            // The operation arm takes no entity spec, so the marker is a syntax error there.
            defect("RoundUp(\$Items/Code, 2) > 0", "MVK_UNEXPECTED_TOKEN")
            defect("RangeAsString(\$Items, 1, 3) == \"a\"", NO_GROUPS)
            sound("RangeAsString(\$Items/Code, 1, 3) == \"a\"")
            defect("DayFromDate(\$Items/Count) > 1", "MVK_NO_DATE")
            sound("DayFromDate(\$Items/ShipOn) > 1")
            defect("ValueAsDate(\$Items/Count, FirstDay) > \"01.01.2020\"", "MVK_INVALID_DATE_TYPE_FOR_OP")
            defect("ValueAsDate(\$Items, FirstDay) > \"01.01.2020\"", NO_GROUPS)
            defect("FieldValueIncludedInValueList(\$Items, \"a\")", NO_GROUPS)
            sound("FieldValueIncludedInValueList(\$Items/Code, \"a\")")
            defect("Valid(Date(\$Items/Count, BirthMonth, BirthYear))", "MVK_WRONG_DATE_FORMAT_FOR_OP")
            defect("Valid(Date(\$Items, BirthMonth, BirthYear))", NO_GROUPS)
            sound("Valid(Date(\$Items/Day, BirthMonth, BirthYear))")
            defect("Sum(\$Items/Count) > 0", "MVK_PARAMSIZE_INVALIDN")
            // In a filter body A12 names no duplicate, marked or not, so the operator's own gate answers.
            sound("Sum(\$Items*/Count, Items*/Count) > 0")
            sound("Sum(Items*/Count, Items*/Count) > 0")
            defect("Sum(\$Items*/Code, Items*/Code) > 0", "MVK_NO_NUMBER")
            defect("Sum(Items*/Code, Items*/Code) > 0", "MVK_NO_NUMBER")
            outside("Items/Count", "FieldFilled(Items/Count) And Sum(Items*/Code, Items*/Code) > 0", "MVK_NO_NUMBER")
            defect("FieldFilled(\$Items)", NO_GROUPS)
            sound("FieldFilled(\$Items/Count)")
            defect("Length(\$Items/ShipOn) > 1", "MVK_NO_STRING")
            sound("Length(\$Items/Code) > 1")

            // An unmarked repeatable group that something addresses is the family's own group class.
            defect("RangeAsString(Items, 1, 3) == \"a\"", NO_GROUPS)
            defect("ValueAsDate(Items, FirstDay) > \"01.01.2020\"", NO_GROUPS)
            outside("Items/Count", "FieldFilled(Items/Count) And RangeAsString(Items, 1, 3) == \"a\"", NO_GROUPS)
            outside("Items/Count", "FieldFilled(Items/Count) And RangeAsString(Items*, 1, 3) == \"a\"", NO_GROUPS)
            outside("Items/Count", "FieldFilled(Items/Count) And ValueAsDate(Items, FirstDay) > \"01.01.2020\"", NO_GROUPS)

            // A rule iterating nothing: an unaddressed `Items` is the reference reader's, marked or not.
            outside("BirthYear", "FieldFilled(BirthYear) And RangeAsString(Items, 1, 3) == \"a\"", NO_WILDCARD)
            outside("BirthYear", "FieldFilled(BirthYear) And ValueAsDate(Items, FirstDay) > \"01.01.2020\"", NO_WILDCARD)
            outside("BirthYear", "FieldFilled(BirthYear) And RoundUpValue(Items, 2) > 0", NO_WILDCARD)
            outside("BirthYear",
                "FieldFilled(BirthYear) And AtLeastOneFieldFilled(Items*/Count Having RangeAsString(\$Items, 1, 3) == \"a\")",
                NO_WILDCARD)
            outside("BirthYear", "FieldFilled(BirthYear) And AtLeastOneFieldFilled(Items*/Count Having " +
                "[Items/Count] > 0 And RoundUpValue(\$Items, 2) > 0)", NO_WILDCARD)
            outside("BirthYear", "FieldFilled(BirthYear) And AtLeastOneFieldFilled(Items*/Count Having " +
                "[Items/Count] > 0 And Valid(Date(\$Items, BirthMonth, BirthYear)))", NO_WILDCARD)
            // Unmarked, the same group is the body's, which addresses it.
            outside("BirthYear",
                "FieldFilled(BirthYear) And AtLeastOneFieldFilled(Items*/Count Having RangeAsString(Items, 1, 3) == \"a\")",
                NO_GROUPS)
        }
    }
}
