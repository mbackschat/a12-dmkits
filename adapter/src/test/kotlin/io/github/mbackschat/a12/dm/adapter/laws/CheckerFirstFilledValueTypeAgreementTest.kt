package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `FirstFilledValue`'s type gate over its EXPANDED fields ([KERNEL-FINDINGS KF391]), with A12's class written out
 * on every row and the portable checker required to name the same one.
 *
 * The list is typed as its first expanded field, and every later field must MATCH that type: one base type, an
 * Enumeration counting as text; for the date-bearing kinds and a date range the same declared format. Two
 * Booleans always match, whatever stored values they declare. A group operand is its fields, so a group mixing
 * text and number is refused by itself. Duplicates and the Confirm gate answer first.
 *
 * Own domain (an order), built so that each row varies one declaration against a same-kind control.
 */
class CheckerFirstFilledValueTypeAgreementTest {

    private val order = DocumentModelDraft.named("order-first-filled").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Title") { }
            .stringField("Sku") { }
            .enumField("Tier") { e -> e.value("BRONZE").value("GOLD") }
            .numberField("Weight") { n -> n.fractionDigits(2) }
            .numberField("Qty") { }
            .booleanField("Gift") { }
            .booleanField("Paid") { }
            .booleanField("Wrap") { b -> b.trueValue("Y").falseValue("N") }
            .booleanField("Seal") { b -> b.trueValue("Y").falseValue("N") }
            .booleanField("Stamp") { b -> b.trueValue("J").falseValue("K") }
            .confirmField("Confirmed") { }
            .dateField("ShipOn") { d -> d.format("yyyy-MM-dd") }
            .dateField("DueOn") { d -> d.format("dd.MM.yyyy") }
            .dateField("PackOn") { d -> d.format("yyyy-MM-dd") }
            .dateRangeField("Window") { r -> r.format("yyyy-MM-dd").separator("/") }
            .dateRangeField("Period") { r -> r.format("dd.MM.yyyy").separator("-") }
            .group("Parcel") { p -> p.stringField("Label") { }.numberField("Grams") { } }
            .group("Names") { n -> n.stringField("First") { }.stringField("Last") { } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun everyExpandedFieldMatchesTheFirst() {
        val predicted = linkedMapOf(
            "two text fields — control" to (rule("Title", "FirstFilledValue(Title, Sku) == [Title]") to ADMITTED),
            "an enumeration beside text" to (rule("Title", "FirstFilledValue(Title, Tier) == [Title]") to ADMITTED),
            "a number beside text" to (rule("Title", "FirstFilledValue(Title, Weight) == [Title]") to VARYING),
            // The fraction digits are not part of the type the list matches on.
            "two numbers with different fraction digits" to (rule("Weight", "FirstFilledValue(Weight, Qty) > 0") to ADMITTED),
            "two dates at one format" to (rule("ShipOn", "FirstFilledValue(ShipOn, PackOn) == [ShipOn]") to ADMITTED),
            "two dates at different formats" to (rule("ShipOn", "FirstFilledValue(ShipOn, DueOn) == [ShipOn]") to VARYING),
            "a date beside a date range" to (rule("ShipOn", "FirstFilledValue(ShipOn, Window) == [ShipOn]") to VARYING),
            "two date ranges at different formats" to (rule("Window", "FirstFilledValue(Window, Period) == [Window]") to VARYING),
            "two Booleans with the default stored values" to (rule("Gift", "FirstFilledValue(Gift, Paid) == [Gift]") to ADMITTED),
            "two Booleans with the same custom stored values" to (rule("Wrap", "FirstFilledValue(Wrap, Seal) == [Wrap]") to ADMITTED),
            // Predicted VARYING, measured admitted: the model check does not read a Boolean's custom stored values,
            // so every two Booleans match, though A12's type comparison asks for equal stored values.
            "a default Boolean beside a custom one" to (rule("Gift", "FirstFilledValue(Gift, Wrap) == [Gift]") to ADMITTED),
            "two Booleans with different custom stored values" to
                (rule("Wrap", "FirstFilledValue(Wrap, Stamp) == [Wrap]") to ADMITTED),
        )
        assertAgreement(predicted)
    }

    @Test
    fun aGroupIsItsFieldsAndTheEarlierGatesAnswerFirst() {
        val predicted = linkedMapOf(
            "a group of text fields" to (rule("Names/First", "FirstFilledValue(Names) == [Names/First]") to ADMITTED),
            "a group mixing text and number" to (rule("Parcel/Label", "FirstFilledValue(Parcel) == [Parcel/Label]") to VARYING),
            "a text group beside a number" to (rule("Names/First", "FirstFilledValue(Names, Weight) == [Names/First]") to VARYING),
            "a Confirm between two mismatched fields" to
                (rule("Title", "FirstFilledValue(Title, Confirmed, Weight) == [Title]") to setOf("MVK_NO_BOOLY_ALLOWED")),
            "a duplicate behind a mismatch" to
                (rule("Title", "FirstFilledValue(Title, Weight, Title) == [Title]") to setOf("MVK_DUPLICATE_PARAM1")),
        )
        assertAgreement(predicted)
    }

    private fun assertAgreement(predicted: Map<String, Pair<DraftRule, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, cell) ->
            val (rule, a12) = cell
            val observed = compare(rule)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun rule(errorField: String, condition: String) = DraftRule.builder()
        .groupPath("/Order").name("FirstGiven").errorEntityRelPath("../$errorField").errorCondition(condition)
        .errorCode("PROBE").severity("ERROR").build()

    private fun compare(rule: DraftRule): Pair<Set<String>, Set<String>> {
        val result = validator.check(order, rule)
        val a12 = if (result.ok()) emptySet() else
            result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        val ours = check(ModelSource(validator.inject(order, rule))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val VARYING = setOf("MVK_VARYING_TYPES_NOT_ALLOWED")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
