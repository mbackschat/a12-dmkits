package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What each operator's argument slots admit, with A12's class written out on every row and the portable
 * checker required to name the same one.
 *
 * `GrammarOnly_ConditionParser.g4` types every argument slot. A rounding operator's place count and a range
 * operator's two bounds are a digit sequence (`z=ZiffernFolge`), so a name, a sign, a fraction or a read there
 * is a token the parser cannot use, and `ValueAsDate`'s day specification is one of two keywords. `Abs`, `Max`, `Min`, the three operation roundings and the amount of a date
 * addition take an `operation`, where a bare name never stands ([KF472](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf472)).
 * A field or list slot takes an `entitySpec`, where a digit sequence is a name
 * ([KF479](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf479)). A constant is a keyword the
 * grammar never calls, and `CustomCondition` is followed by a name, never an argument list.
 *
 * Own domain: an order with a text code, two quantities, a placement date and repeatable lines.
 */
class CheckerArgumentSlotAgreementTest {

    @Test
    fun eachArgumentSlotAdmitsOnlyItsOwnKind() {
        val rows = linkedMapOf(
            // A digit sequence: the place count of a rounding, the bounds of a range.
            "RoundUpValue(Total, 2) > 0" to ADMITTED,
            "RoundUpValue(Total) > 0" to ADMITTED,
            "RoundUpValue(Total, NoSuch) > 0" to TOKEN,
            "RoundUpValue(Total, Qty) > 0" to TOKEN,
            "RoundUpValue(Total, 2And) > 0" to TOKEN,
            "RoundUpValue(Total, -2) > 0" to TOKEN,
            "RoundUpValue(Total, - 2) > 0" to TOKEN,
            "RoundUpValue(Total, 2.5) > 0" to TOKEN,
            "RoundUpValue(Total, [Qty]) > 0" to TOKEN,
            "RoundUpValue(Total, 1, 2) > 0" to TOKEN,
            "RoundUp([Total], 2) > 0" to ADMITTED,
            "RoundUp([Total], Qty) > 0" to TOKEN,
            "RangeAsNumber(Code, 1, 2) > 0" to ADMITTED,
            "RangeAsNumber(Code, 1, Qty) > 0" to TOKEN,
            "RangeAsNumber(Code, -1, 2) > 0" to TOKEN,
            "RangeAsNumber(Code, 1) > 0" to TOKEN,
            // An operation: a bare name never stands there.
            "Abs([Total]) > 0" to ADMITTED,
            "Abs(Total) > 0" to TOKEN,
            "RoundUp(Total, 2) > 0" to TOKEN,
            "Max([Total], 1) > 0" to ADMITTED,
            "Max(Total, 1) > 0" to TOKEN,
            "Max([Total]) > 0" to ADMITTED,
            "Abs([Total], 1) > 0" to TOKEN,
            "AddDays(Placed) > [Placed]" to TOKEN,
            "AddDays(Placed, 3) > [Placed]" to ADMITTED,
            "AddDays(Placed, [Qty]) > [Placed]" to ADMITTED,
            "AddDays(Placed, Qty) > [Placed]" to TOKEN,
            "AddDays(Placed, 3And) > [Placed]" to TOKEN,
            // A day specification: `tagSpec: 'ErsterTag' | 'LetzterTag'`, no other token.
            "ValueAsDate(Estimated, FirstDay) > \"01.01.2020\"" to ADMITTED,
            "ValueAsDate(Estimated, LastDay) > \"01.01.2020\"" to ADMITTED,
            "ValueAsDate(Estimated, FirstDayAnd) > \"01.01.2020\"" to TOKEN,
            "ValueAsDate(Estimated, Code) > \"01.01.2020\"" to TOKEN,
            "ValueAsDate(Estimated, Today) > \"01.01.2020\"" to TOKEN,
            "ValueAsDate(Estimated) > \"01.01.2020\"" to TOKEN,
            // An entity spec: a digit sequence is a name.
            "FieldFilled(5)" to ENTITY,
            "AbsValue(5) > 0" to ENTITY,
            "MinValue(1 + Items*/Count) > 1" to ENTITY,
            // A constant is never called.
            "[Placed] < Today" to ADMITTED,
            "[Placed] < Today (" to TOKEN,
            "[Placed] < Today()" to TOKEN,
            "[Placed] < Now()" to TOKEN,
            // The constant's own check runs as it closes, ahead of the `(` the parser then cannot use: this model
            // declares no base year.
            "BaseYear(1) > 0" to setOf("MVK_NO_BASE_YEAR"),
            // `CustomCondition` takes a name, not an argument list.
            "CustomCondition Ext" to ADMITTED,
            "CustomCondition (Ext)" to TOKEN,
            "CustomCondition And FieldFilled(Qty)" to TOKEN,
            "CustomCondition" to setOf("MVK_EXPECTED_TOKEN_NOT_FOUND"),
            "CustomCondition 5" to ADMITTED,
            // Every other predicate is a call: a bare name after one is a token the parser cannot use.
            "FieldFilled Qty" to TOKEN,
        )
        val wrong = rows.mapNotNull { (text, a12) ->
            val observed = compare("FieldFilled(Total) And $text")
            val expected = a12 to (OURS[text] ?: a12)
            if (observed == expected) null else "$text: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "${wrong.size} of ${rows.size} rows disagree:\n"))
    }

    /**
     * The table is keyed by operator id, so a German model reads the same slots through its own spellings; the
     * English spellings there are ordinary names, which the parser cannot use either.
     */
    @Test
    fun aGermanModelReadsTheSameSlotsThroughItsOwnSpellings() {
        val rows = linkedMapOf(
            "AufrundenWert(Total, 2) > 0" to ADMITTED,
            "AufrundenWert(Total, Qty) > 0" to TOKEN,
            "Abs(Total) > 0" to TOKEN,
            "WertAlsDatum(Estimated, ErsterTag) > \"01.01.2020\"" to ADMITTED,
            "WertAlsDatum(Estimated, FirstDay) > \"01.01.2020\"" to TOKEN,
            "[Placed] < Heute (" to TOKEN,
            "ApplikationsBedingung 5" to ADMITTED,
            "ApplikationsBedingung (Ext)" to TOKEN,
        )
        val wrong = rows.mapNotNull { (text, a12) ->
            val observed = compare("FeldAngegeben(Total) Und $text", orderDe)
            if (observed == a12 to a12) null else "$text: expected (A12, ours) ${a12 to a12}, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "${wrong.size} of ${rows.size} rows disagree:\n"))
    }

    private fun compare(condition: String, model: String = order): Pair<Set<String>, Set<String>> {
        val rule = DraftRule.builder().groupPath("/Order").name("SlotProbe").errorEntityRelPath("../Total")
            .errorCondition(condition).errorCode("SLOT_PROBE").severity("ERROR").build()
        val a12 = validator.check(model, rule).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(model, rule))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private val validator = RuleValidator()

    private val order = DocumentModelDraft.named("order-slots").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Code") { }
            .numberField("Total") { n -> n.fractionDigits(2) }
            .numberField("Qty") { n -> n.fractionDigits(0) }
            .dateField("Placed") { d -> d.format("yyyy-MM-dd") }
            .dateField("Estimated") { d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL) }
            .group("Items") { i -> i.repeatable(9).numberField("Count") { n -> n.fractionDigits(0) } } }
        .build().toJson()

    private val orderDe = ModelDocument.read(order).setConditionLanguage("de_DE").toJson()

    private companion object {
        val ADMITTED = emptySet<String>()
        val TOKEN = setOf("MVK_UNEXPECTED_TOKEN")
        val ENTITY = setOf("MVK_INVALID_ENTITY")

        /**
         * The rows where the checker answers otherwise, with what it answers: a digit sequence that leads an
         * entity slot, which the reference scanner does not read as a name
         * ([CONSISTENCY-GAPS DG93](../../../../../../../../../../../docs/CONSISTENCY-GAPS.md)).
         */
        val OURS: Map<String, Set<String>> = mapOf(
            "FieldFilled(5)" to ADMITTED,
            "AbsValue(5) > 0" to ADMITTED,
            "MinValue(1 + Items*/Count) > 1" to TOKEN,
        )
    }
}
