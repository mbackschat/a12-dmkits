package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.signatures
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The fill polarity of operand-list `Min`/`Max`, `Abs`, the `AbsValue` and `RoundAccountingValue` field forms, a
 * `Length` and a filtered `NumberOfFilledFields`, a12-kernel-lean SPEC-2026-10-28-05 ([KERNEL-FINDINGS KF639]).
 * Each rule compares one form over the empty signed `Delta`, the empty unsigned `Fee`, the empty `Code` or the
 * lines' quantities against filled `Base` (5) and `Cap` (7), so a fired rule's type says which way filling the
 * empty operand could move it. `MaxBase` is the separating row: an empty signed operand beside a filled one fires
 * VALUE, where a rule that let either operand shrink would fire OMISSION. Every row is three-engine agreed.
 *
 * Own domain (an online shop's quote).
 */
class OperandFormFillPolarityDiffTest {

    private val base = DocumentModelDraft.named("quote-fill-polarity").locale("en_US")
        .rootGroup("Quote") { q -> q.stringField("Ref") { }
            .numberField("Delta") { it.fractionDigits(0) }
            .numberField("Fee") { it.fractionDigits(0).positivesOnly() }
            .numberField("Base") { it.fractionDigits(0) }
            .numberField("Cap") { it.fractionDigits(0) }
            .stringField("Code") { }.stringField("Memo") { }
            .group("Lines") { l -> l.repeatable(3).numberField("Qty") { it.fractionDigits(0) }
                .stringField("Flag") { }.stringField("Gift") { } } }
        .build().toJson()

    private val rules = linkedMapOf(
        "MinFee" to "Min([Fee], [Base]) < 10",
        "MinBoth" to "Min([Base], [Cap]) < 10",
        "MinDelta" to "Min([Delta], [Base]) > -10",
        "MaxBase" to "Max([Delta], [Base]) > 3",
        "MaxFee" to "Max([Fee], [Base]) < 10",
        "MaxBoth" to "Max([Base], [Cap]) > 3",
        "AbsAbove" to "Abs([Delta]) > -1",
        "AbsBelow" to "Abs([Delta]) < 10",
        "AbsBase" to "Abs([Base]) < 10",
        "AbsFee" to "Abs([Fee]) > -1",
        "AbsValueAbove" to "AbsValue(Delta) > -1",
        "AbsValueBelow" to "AbsValue(Delta) < 10",
        "AbsValueBase" to "AbsValue(Base) < 10",
        "RoundDelta" to "RoundAccountingValue(Delta, 0) > -1",
        "RoundFeeAbove" to "RoundAccountingValue(Fee, 0) > -1",
        "RoundFeeBelow" to "RoundAccountingValue(Fee, 0) < 10",
        "LengthBelow" to "Length(Code) < 3",
        "LengthZero" to "Length(Code) == 0",
        "LengthMemo" to "Length(Memo) < 10",
        "LengthAbove" to "Length(Code) > -1",
        "CountUngifted" to "NumberOfFilledFields(Lines*/Qty Having FieldNotFilled(Lines/Gift)) >= 1",
        "CountAll" to "NumberOfFilledFields(Lines*/Qty) >= 1",
        "CountFlagged" to "NumberOfFilledFields(Lines*/Qty Having [Lines/Flag] == \"Y\") >= 1",
        "CountFlaggedNone" to "NumberOfFilledFields(Lines*/Qty Having [Lines/Flag] == \"Y\") < 1",
    )

    private val model = admitted(rules.entries.fold(base) { json, (code, condition) ->
        RuntimeLaws.withRule(json, "/Quote", "${code}Rule", "../Ref", code, "FieldFilled(Ref) And $condition")
    })

    private fun quote(extra: String, lines: String) =
        """{"Quote":{"Ref":"q","Base":5,"Cap":7,"Memo":"ab"$extra,"Lines":$lines}}"""

    @Test
    fun eachFormTypesItsFiredComparisonAsAllThreeEnginesAgree() {
        val observed = linkedMapOf(
            "nothing flagged" to quote("", """[{"Qty":1}]"""),
            "one line flagged" to quote("", """[{"Qty":1,"Flag":"Y"}]"""),
            "every line unflagged" to quote("", """[{"Qty":1,"Flag":"N"},{"Qty":2,"Flag":"N"},{"Qty":3,"Flag":"N"}]"""),
            "a negative delta" to quote(""","Delta":-3""", """[{"Qty":1,"Gift":"g"}]"""),
        ).mapValues { (_, document) -> signatures(model, document) }
        val value = listOf("AbsAbove", "AbsBase", "AbsFee", "AbsValueAbove", "AbsValueBase", "LengthAbove",
            "LengthMemo", "MaxBase", "MaxBoth", "MinBoth", "CountAll", "RoundFeeAbove")
        val omission = listOf("AbsBelow", "AbsValueBelow", "LengthBelow", "LengthZero", "MaxFee", "MinDelta", "MinFee",
            "CountUngifted", "RoundDelta", "RoundFeeBelow")
        fun fired(value: List<String>, omission: List<String>) =
            (value.map { "$it|VALUE_ERROR|/Quote[1]/Ref" } + omission.map { "$it|OMISSION_ERROR|/Quote[1]/Ref" }).sorted()
        assertEquals(linkedMapOf(
            "nothing flagged" to fired(value, omission + "CountFlaggedNone"),
            "one line flagged" to fired(value, omission + "CountFlagged"),
            "every line unflagged" to fired(value, omission + "CountFlaggedNone"),
            "a negative delta" to fired(value + listOf("AbsBelow", "AbsValueBelow", "MinDelta"),
                omission - setOf("AbsBelow", "AbsValueBelow", "MinDelta", "CountUngifted", "RoundDelta") + "CountFlaggedNone"),
        ), observed)
    }
}
