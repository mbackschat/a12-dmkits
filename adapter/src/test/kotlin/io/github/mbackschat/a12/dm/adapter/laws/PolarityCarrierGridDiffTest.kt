package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.signatures
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The polarity a fired comparison takes over each carrier a12-kernel-lean SPEC-2026-10-26-08, -10 and
 * SPEC-2026-10-27-02 left unmeasured ([KERNEL-FINDINGS KF639]). Every row is three-engine agreed.
 *
 * - A filtered count that kept nothing is VALUE once it is below the stored comparand and OMISSION otherwise.
 * - `NumberOfDifferentValues` over a closed tail is OMISSION while a row is still empty and VALUE once both are filled.
 * - A filtered `MaxValue` is OMISSION and the plain one VALUE, though the filter keeps every row.
 * - `CurrentRepetition` against an empty number is OMISSION.
 * - An empty Confirm reads false and fires OMISSION; an empty Boolean or Enumeration comparison is not evaluated.
 *
 * Own domain (an online order).
 */
class PolarityCarrierGridDiffTest {

    private val base = DocumentModelDraft.named("order-polarity-carriers").locale("en_US")
        .rootGroup("Order") { o -> o
            .numberField("Count") { it.fractionDigits(0) }
            .numberField("Limit") { it.fractionDigits(0) }
            .field("Express", FieldKind.BOOLEAN)
            .field("Agreed", FieldKind.CONFIRM)
            .enumField("Tier") { it.value("GOLD").value("SILVER") }
            .dateField("Cutoff") { it.format("yyyy-MM-dd") }
            .group("Items") { i -> i.repeatable(10).numberField("Qty") { it.fractionDigits(0) }.stringField("Flag") { } }
            .group("Lines") { l -> l.repeatable(2).numberField("V") { it.fractionDigits(0) } }
            .group("Slots") { s -> s.repeatable(2).dateField("On") { it.format("yyyy-MM-dd") }.stringField("Mark") { } } }
        .build().toJson()

    private val rules = linkedMapOf(
        "KeptNothing" to ("/Order" to ("../Count" to
            "[Count] != NumberOfValueInFields(1 In Items*/Qty Having [Items/Flag] == \"Y\")")),
        "Distinct" to ("/Order" to ("../Count" to "FieldFilled(Count) And NumberOfDifferentValues(Lines*/V) < 2")),
        "FilteredMax" to ("/Order" to ("../Cutoff" to
            "[Cutoff] < MaxValue(Slots*/On Having [Slots/Mark] == \"Y\")")),
        "PlainMax" to ("/Order" to ("../Cutoff" to "[Cutoff] < MaxValue(Slots*/On)")),
        "Repetition" to ("/Order/Items" to ("../Qty" to "FieldFilled(Qty) And CurrentRepetition(/Order/Items) > [/Order/Limit]")),
        "BoolFalse" to ("/Order" to ("../Count" to "FieldFilled(Count) And [Express] == False")),
        "BoolNotTrue" to ("/Order" to ("../Count" to "FieldFilled(Count) And [Express] != True")),
        "ConfirmFalse" to ("/Order" to ("../Count" to "FieldFilled(Count) And [Agreed] != True")),
        "EnumNot" to ("/Order" to ("../Count" to "FieldFilled(Count) And [Tier] != \"GOLD\"")),
    )

    private val model = admitted(rules.entries.fold(base) { json, (code, rule) ->
        val (group, target) = rule
        RuntimeLaws.withRule(json, group, "${code}Rule", target.first, code, target.second)
    })

    @Test
    fun eachCarrierTypesItsFiredComparisonAsAllThreeEnginesAgree() {
        val observed = linkedMapOf(
            "kept nothing, stored below" to """{"Order":{"Count":-5,"Items":[{"Qty":1,"Flag":"N"},{"Qty":2,"Flag":"N"}]}}""",
            "distinct, closed tail, one empty" to """{"Order":{"Count":1,"Lines":[{"V":5},{}]}}""",
            "distinct, closed tail, both filled" to """{"Order":{"Count":1,"Lines":[{"V":5},{"V":5}]}}""",
            "extremum, closed tail, filter keeps all" to """{"Order":{"Cutoff":"2020-01-01","Slots":[""" +
                """{"On":"2024-01-01","Mark":"Y"},{"On":"2024-02-01","Mark":"Y"}]}}""",
            "repetition beside an empty number" to """{"Order":{"Count":1,"Items":[{"Qty":1}]}}""",
            "empty boolean, confirm and enumeration" to """{"Order":{"Count":1}}""",
        ).mapValues { (_, document) -> signatures(model, document) }
        assertEquals(linkedMapOf(
            "kept nothing, stored below" to listOf("ConfirmFalse|OMISSION_ERROR|/Order[1]/Count",
                "Distinct|OMISSION_ERROR|/Order[1]/Count", "KeptNothing|VALUE_ERROR|/Order[1]/Count",
                "Repetition|OMISSION_ERROR|/Order[1]/Items[1]/Qty", "Repetition|OMISSION_ERROR|/Order[1]/Items[2]/Qty"),
            "distinct, closed tail, one empty" to listOf("ConfirmFalse|OMISSION_ERROR|/Order[1]/Count",
                "Distinct|OMISSION_ERROR|/Order[1]/Count", "KeptNothing|OMISSION_ERROR|/Order[1]/Count"),
            "distinct, closed tail, both filled" to listOf("ConfirmFalse|OMISSION_ERROR|/Order[1]/Count",
                "Distinct|VALUE_ERROR|/Order[1]/Count", "KeptNothing|OMISSION_ERROR|/Order[1]/Count"),
            "extremum, closed tail, filter keeps all" to listOf("FilteredMax|OMISSION_ERROR|/Order[1]/Cutoff",
                "PlainMax|VALUE_ERROR|/Order[1]/Cutoff"),
            "repetition beside an empty number" to listOf("ConfirmFalse|OMISSION_ERROR|/Order[1]/Count",
                "Distinct|OMISSION_ERROR|/Order[1]/Count", "KeptNothing|OMISSION_ERROR|/Order[1]/Count",
                "Repetition|OMISSION_ERROR|/Order[1]/Items[1]/Qty"),
            "empty boolean, confirm and enumeration" to listOf("ConfirmFalse|OMISSION_ERROR|/Order[1]/Count",
                "Distinct|OMISSION_ERROR|/Order[1]/Count", "KeptNothing|OMISSION_ERROR|/Order[1]/Count"),
        ), observed)
    }
}
