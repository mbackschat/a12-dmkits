package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.signatures
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An index cell the generated checks report is unknown to an authored rule that reads it, a12-kernel-lean
 * SPEC-2026-10-28-06 ([KERNEL-FINDINGS KF638]). An empty key, nominated in the non-repeatable `Holder` or in
 * the repeatable `Lines`, draws `mandatoryField` and leaves every rule reading it silent, its negation included,
 * while the unnominated `Payer` twin is an ordinary empty that fires `FieldNotFilled` and reads past to `Alt`. A
 * duplicated key draws `uniqueIndex` on both rows and silences both rows' reads of it, while the third row, keyed
 * apart, still fires. `Live` and `LineLive` read no key and fire on every document. Every row is three-engine agreed.
 *
 * Own domain (an insurance claim's holder, payer and lines).
 */
class IndexFindingUnknownToRulesDiffTest {

    private val base = DocumentModelDraft.named("claim-index-findings").locale("en_US")
        .rootGroup("Claim") { c -> c.stringField("Ref") { }
            .group("Holder") { h -> h.numberField("HolderKey") { it.fractionDigits(0) }
                .numberField("Alt") { it.fractionDigits(0) }.stringField("Name") { }.indexField("HolderKey") }
            .group("Payer") { p -> p.numberField("PayerKey") { it.fractionDigits(0) }.numberField("Alt") { it.fractionDigits(0) } }
            .group("Lines") { l -> l.repeatable(5).indexField("LineKey")
                .numberField("LineKey") { it.fractionDigits(0) }.numberField("Alt") { it.fractionDigits(0) } } }
        .build().toJson()

    private val model = admitted(listOf(
        listOf("/Claim", "Live", "../Ref", "FieldFilled(Ref)"),
        listOf("/Claim", "HolderKeyMissing", "../Ref", "FieldFilled(Ref) And FieldNotFilled(Holder/HolderKey)"),
        listOf("/Claim", "PayerKeyMissing", "../Ref", "FieldFilled(Ref) And FieldNotFilled(Payer/PayerKey)"),
        listOf("/Claim", "HolderFirst", "../Ref", "FieldFilled(Ref) And FirstFilledValue(Holder/HolderKey, Holder/Alt) == 5"),
        listOf("/Claim", "PayerFirst", "../Ref", "FieldFilled(Ref) And FirstFilledValue(Payer/PayerKey, Payer/Alt) == 5"),
        listOf("/Claim/Lines", "LineLive", "../Alt", "FieldFilled(Alt)"),
        listOf("/Claim/Lines", "LineKeyMissing", "../Alt", "FieldFilled(Alt) And FieldNotFilled(LineKey)"),
        listOf("/Claim/Lines", "LineKeyPresent", "../Alt", "FieldFilled(Alt) And FieldFilled(LineKey)"),
        listOf("/Claim/Lines", "LineKeyOne", "../Alt", "FieldFilled(Alt) And [LineKey] == 1"),
    ).fold(base) { json, (group, code, target, condition) ->
        RuntimeLaws.withRule(json, group, "${code}Rule", target, code, condition)
    })

    @Test
    fun anEmptyOrDuplicatedKeyIsUnknownToEveryRuleReadingIt() {
        val observed = linkedMapOf(
            "empty keys" to """{"Claim":{"Ref":"R","Holder":{"Alt":5,"Name":"N"},"Payer":{"Alt":5},"Lines":[{"Alt":5}]}}""",
            "filled keys" to """{"Claim":{"Ref":"R","Holder":{"HolderKey":1,"Alt":5},"Payer":{"PayerKey":1,"Alt":5},""" +
                """"Lines":[{"LineKey":2,"Alt":5}]}}""",
            "a duplicated line key" to """{"Claim":{"Ref":"R","Holder":{"HolderKey":1,"Alt":5},"Payer":{"PayerKey":1,"Alt":5},""" +
                """"Lines":[{"LineKey":1,"Alt":5},{"LineKey":1,"Alt":6},{"LineKey":2,"Alt":7}]}}""",
        ).mapValues { (_, document) -> signatures(model, document) }
        val live = listOf("Live|VALUE_ERROR|/Claim[1]/Ref")
        fun line(code: String, row: Int) = "$code|VALUE_ERROR|/Claim[1]/Lines[$row]/Alt"
        assertEquals(linkedMapOf(
            "empty keys" to (live + listOf(line("LineLive", 1),
                "PayerFirst|OMISSION_ERROR|/Claim[1]/Ref", "PayerKeyMissing|OMISSION_ERROR|/Claim[1]/Ref",
                "mandatoryField|OMISSION_ERROR|/Claim[1]/Holder[1]/HolderKey",
                "mandatoryField|OMISSION_ERROR|/Claim[1]/Lines[1]/LineKey")).sorted(),
            "filled keys" to (live + listOf(line("LineLive", 1), line("LineKeyPresent", 1))).sorted(),
            "a duplicated line key" to (live + listOf(line("LineLive", 1), line("LineLive", 2), line("LineLive", 3),
                line("LineKeyPresent", 3), "uniqueIndex|VALUE_ERROR|/Claim[1]/Lines[1]/LineKey",
                "uniqueIndex|VALUE_ERROR|/Claim[1]/Lines[2]/LineKey")).sorted(),
        ), observed)
    }
}
