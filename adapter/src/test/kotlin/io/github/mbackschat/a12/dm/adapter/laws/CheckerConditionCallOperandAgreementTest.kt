package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A condition call standing where an operation is expected, against the portable checker (a12-kernel-lean
 * SPEC-2026-10-14-05). A12 answers it with the token class at the call's keyword, before the call's own reads are
 * checked, so an unresolved name inside it changes nothing. A gate belonging to a production that completed
 * before the call answers first: rounding text keeps the Number class and a date extractor or date shift over text
 * keeps the Date class, while each one's correctly typed twin falls through to the token class.
 *
 * Own domain (a parcel shipment with a tracking code, a weight and a ship date).
 */
class CheckerConditionCallOperandAgreementTest {

    private val shipment = DocumentModelDraft.named("parcel-shipment").locale("en_US")
        .rootGroup("Shipment") { s -> s
            .stringField("Tracking") { }
            .numberField("Weight") { n -> n.fractionDigits(0, 2) }
            .dateField("Shipped") { } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aConditionCallInAnOperationPositionDrawsTheTokenClass() {
        assertAgreement(linkedMapOf(
            "an operation's operand" to ("Abs(FieldFilled(Weight)) > 0" to TOKEN),
            "an operation's operand naming no field" to ("Abs(FieldFilled(Undeclared)) > 0" to TOKEN),
            "an operation's operand over text" to ("Abs(FieldFilled(Tracking)) > 0" to TOKEN),
            "the first of two operands" to ("Max(FieldFilled(Weight), 2) > 0" to TOKEN),
            "a value-list call as an operand" to ("Abs(FieldValueIncludedInValueList(Tracking, \"PX\")) > 0" to TOKEN),
            "a group call as an operand" to ("Abs(GroupFilled(/Shipment)) > 0" to TOKEN),
            "an arithmetic right operand" to ("[Weight] + FieldFilled(Weight) > 0" to TOKEN),
            "an arithmetic left operand" to ("FieldFilled(Weight) + 1 > 0" to TOKEN),
            "a comparison's left side" to ("FieldFilled(Weight) > 0" to TOKEN),
            "a comparison's right side" to ("[Weight] > FieldFilled(Weight)" to TOKEN),
        ))
    }

    @Test
    fun aGateOfAProductionCompletedBeforeTheCallAnswersFirst() {
        assertAgreement(linkedMapOf(
            "text rounded inside an operand" to ("Abs(RoundUpValue(Tracking, 2) + FieldFilled(Weight)) > 0" to NUMBER),
            "a Number rounded inside an operand — the twin" to
                ("Abs(RoundUpValue(Weight, 2) + FieldFilled(Weight)) > 0" to TOKEN),
            "text rounded before an arithmetic operand" to ("RoundUpValue(Tracking, 2) + FieldFilled(Weight) > 0" to NUMBER),
            "text rounded before a comparison side" to ("RoundUpValue(Tracking, 2) > FieldFilled(Weight)" to NUMBER),
            "a year of text — control" to ("YearFromDate(Tracking) > 1" to DATE),
            "a year of text in a comparison standing as an operand" to ("Abs(YearFromDate(Tracking) > 1) > 0" to DATE),
            "a year of a Date in the same place — the twin" to ("Abs(YearFromDate(Shipped) > 1) > 0" to TOKEN),
            "a year of text before a call operand" to ("Abs(YearFromDate(Tracking) + FieldFilled(Weight)) > 0" to DATE),
            "a year of text before a doubled connective" to ("YearFromDate(Tracking) > 1 And And FieldFilled(Weight)" to DATE),
            "a year of a Date before a doubled connective — the twin" to
                ("YearFromDate(Shipped) > 1 And And FieldFilled(Weight)" to TOKEN),
            "a sound leaf, then a year of text before a doubled connective" to
                ("FieldFilled(Shipped) And YearFromDate(Tracking) > 1 And And FieldFilled(Weight)" to DATE),
            "a shift of text — control" to ("AddDays(Tracking, 1) > Today" to DATE),
            "a shift of text before a doubled connective" to ("AddDays(Tracking, 1) > Today And And FieldFilled(Weight)" to DATE),
        ))
    }

    private fun assertAgreement(predicted: Map<String, Pair<String, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (condition, a12) = probe
            val observed = verdicts(condition)
            val expected = a12 to a12
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun verdicts(condition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Shipment", "ProbeRule", "../Weight", condition, "PROBE", "ERROR")
        val result = validator.check(shipment, draft)
        val a12 = if (result.ok()) emptySet() else result.diagnostics().map { it.codeOrSummary() }.toSet()
        val ours = rejectingCodes(validator.inject(shipment, draft)).toSet()
        return a12 to ours
    }

    private companion object {
        val TOKEN = setOf("MVK_UNEXPECTED_TOKEN")
        val NUMBER = setOf("MVK_NO_NUMBER")
        val DATE = setOf("MVK_NO_DATE")
    }
}
