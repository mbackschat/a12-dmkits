package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A group or a star where an operand gate wants one field, on the three carriers the near-miss sweep found
 * silent or answered with the wrong class (`CheckerNearMissSweepTest`): a `Date` construction's slot, a
 * `$`-marked `CurrentRepetition` inside a filter body, and a `SumOfProducts` operand. A12 is pinned on every
 * row and the checker must name the same classes (KERNEL-FINDINGS KF598).
 *
 * Own domain: a shop customer and the items of an order.
 */
class CheckerGroupAndStarOperandAgreementTest {

    private val customer = DocumentModelDraft.named("customer-items").locale("en_US")
        .rootGroup("Customer") { c -> c
            .numberField("BirthDay") { n -> n.fractionDigits(0).min("1").max("31") }
            .numberField("BirthMonth") { n -> n.fractionDigits(0).min("1").max("12") }
            .numberField("BirthYear") { n -> n.fractionDigits(0).min("1800").max("2200") }
            .dateField("OrderedOn") { d -> d.format("yyyy-MM-dd") }
            .numberField("Total") { n -> n.fractionDigits(4) }
            .group("BillingAddress") { a -> a.stringField("Street") { } }
            .group("Items") { i -> i.repeatable(20)
                .numberField("Count") { n -> n.fractionDigits(0) }
                .numberField("Month") { n -> n.fractionDigits(0).min("1").max("12") }
                .numberField("UnitPrice") { n -> n.fractionDigits(2) }
                .numberField("UnitWeight") { n -> n.fractionDigits(2) } } }
        .build().toJson()

    private val validator = RuleValidator()

    private fun judge(errorField: String, condition: String): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Customer", "OperandProbe", "../$errorField", condition, "PROBE", "ERROR")
        val a12 = validator.check(customer, draft).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
        }
        val ours = rejectingCodes(validator.inject(customer, draft))
        return a12.sorted() to ours.sorted()
    }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.third }, ROWS.mapValues { (_, row) -> judge(row.first, row.second).first })
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        val verdicts = ROWS.mapValues { (_, row) -> judge(row.first, row.second) }
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private companion object {
        const val NO_GROUPS = "MVK_NO_GROUPS_ALLOWED"
        const val NO_WILDCARDS = "MVK_NO_WILDCARDS_ALLOWED"
        const val NO_FIELDS = "MVK_NO_FIELDS_ALLOWED"
        const val BODY = "AtLeastOneFieldFilled(Items*/Count Having CurrentRepetition(Items) != %s And [Items/Count] == [\$Items/Count])"

        fun row(errorField: String, condition: String, vararg a12: String) = Triple(errorField, condition, a12.toList())

        val ROWS: Map<String, Triple<String, String, List<String>>> = linkedMapOf(
            // A Date construction's slot: A12 asks "a field?" before "no star?" (`checkParamIfEntity`).
            "a group in the month slot" to row("BirthDay",
                "AllFieldsFilled(BirthDay, BirthMonth, BirthYear) And Invalid(Date(BirthDay, BillingAddress, BirthYear))", NO_GROUPS),
            "a group in the day slot" to row("BirthDay",
                "FieldFilled(BirthDay) And Invalid(Date(BillingAddress, BirthMonth, BirthYear))", NO_GROUPS),
            "a starred group in the month slot" to row("BirthDay",
                "FieldFilled(BirthDay) And Invalid(Date(BirthDay, Items*, BirthYear))", NO_GROUPS),
            "a starred field in the month slot" to row("BirthDay",
                "FieldFilled(BirthDay) And Invalid(Date(BirthDay, Items*/Month, BirthYear))", NO_WILDCARDS),
            "three fields" to row("BirthDay",
                "AllFieldsFilled(BirthDay, BirthMonth, BirthYear) And Invalid(Date(BirthDay, BirthMonth, BirthYear))"),
            "a group in a compared construction" to row("OrderedOn",
                "FieldFilled(OrderedOn) And [OrderedOn] == Date(BirthDay, BillingAddress, BirthYear)", NO_GROUPS),
            // CurrentRepetition inside a filter body: "a group?" before "no star?" (`CheckGroupOpImpl`).
            "a marked starred repetition, spaced" to row("Items/Count",
                "FieldFilled(Items/Count) And " + BODY.format("CurrentRepetition(\$Items* )"), NO_WILDCARDS),
            "a marked starred repetition" to row("Items/Count",
                "FieldFilled(Items/Count) And " + BODY.format("CurrentRepetition(\$Items*)"), NO_WILDCARDS),
            "a marked repetition" to row("Items/Count",
                "FieldFilled(Items/Count) And " + BODY.format("CurrentRepetition(\$Items)")),
            "a marked field" to row("Items/Count",
                "FieldFilled(Items/Count) And " + BODY.format("CurrentRepetition(\$Items/Count)"), NO_FIELDS),
            "an unmarked starred repetition in the body" to row("Items/Count",
                "FieldFilled(Items/Count) And " + BODY.format("CurrentRepetition(Items*)"), NO_WILDCARDS),
            // A marked operand is addressed by the rule's own iteration, and this rule iterates nothing.
            "a marked field in a rule iterating nothing" to row("Total",
                "FieldFilled(Total) And AtLeastOneFieldFilled(Items*/Count Having CurrentRepetition(\$Items/Count) > 0)",
                "MVK_NO_WILDCARD"),
            "a starred repetition outside a filter" to row("Items/Count",
                "FieldFilled(Items/Count) And CurrentRepetition(Items*) > 1", NO_WILDCARDS),
            // SumOfProducts: "only fields" before "exactly two" (`CheckFeldListenOpImpl`), and both at the call's
            // own `)`, ahead of any syntax error later in the text.
            "a group operand before a stray comma" to row("Total",
                "FieldFilled(Total) And [Total] != SumOfProducts(Items*) /Count, Items*/UnitWeight)", NO_GROUPS),
            // Expanded, the group repeats the field beside it, which the list reader refuses first.
            "a group beside a field it holds" to row("Total",
                "FieldFilled(Total) And [Total] != SumOfProducts(Items*, Items*/UnitWeight)", "MVK_DUPLICATE_PARAM2"),
            "a lone group" to row("Total", "FieldFilled(Total) And [Total] != SumOfProducts(Items*)", NO_GROUPS),
            "two groups" to row("Total", "FieldFilled(Total) And [Total] != SumOfProducts(BillingAddress, Items*)", NO_GROUPS),
            // The root-group gate runs before "only fields".
            "a root group" to row("Total", "FieldFilled(Total) And [Total] != SumOfProducts(/Customer)",
                "MVK_ROOT_GROUP_REFERENCED"),
            "two fields" to row("Total",
                "FieldFilled(Total) And [Total] != SumOfProducts(Items*/UnitPrice, Items*/UnitWeight)"),
        )
    }
}
