package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Test

/**
 * A key over an index declared on the root or on a fixed group, read by a rule and by computations
 * (a12-kernel-lean SPEC-2026-10-28-08, [KERNEL-FINDINGS KF478], [KERNEL-FINDINGS KF522]).
 *
 * Two channels are at stake beside the selection itself. `GroupFilled(/Policy For "P1")` keys the root, and A12
 * expands no root group, so the message references only the error field and the index field. A computation
 * reading through such a key reads the index field too, so an empty one — required while its group holds
 * content — is among that computation's operand errors, whether the index sits on the root, on a fixed group, or
 * on a fixed group inside each row of a repeatable one. An error-text parameter keyed as the condition keys its read
 * renders the field's name. Every document is three-engine agreed on messages, their texts, outcomes and operand
 * errors.
 *
 * Own domain (an insurance policy's cover; a fleet's insured unit and the legs of its trips).
 */
class SingleInstanceKeyOperandDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("policy-single-instance-key").locale("en_US")
            .rootGroup("Policy") { p -> p.indexField("PolicyRef").stringField("PolicyRef") { }.stringField("Note") { }
                .numberField("Total") { it.fractionDigits(0) }
                .group("Cover") { c -> c.numberField("Amount") { it.fractionDigits(0) } } }
            .rootGroup("Fleet") { f -> f.stringField("Name") { }.numberField("PowerCopy") { it.fractionDigits(0) }
                .group("Unit") { u -> u.indexField("UnitKey").stringField("UnitKey") { }
                    .numberField("Power") { it.fractionDigits(0) } }
                .group("Trips") { t -> t.repeatable(3).stringField("TripNote") { }
                    .numberField("Copy") { it.fractionDigits(0) }
                    .group("Leg") { l -> l.indexField("LegKey").stringField("LegKey") { }
                        .numberField("Fee") { it.fractionDigits(0) } } } }
            .build().toJson()
        val withRule = RuntimeLaws.withRule(base, "/Policy", "PolicyPresent", "../Note", "PolicyPresent",
            "FieldFilled(Note) And GroupFilled(/Policy For \"P1\")").let {
            RuntimeLaws.withRuleMessages(it, "/Policy", "CoverNamed", "../Note", "CoverNamed",
                "FieldFilled(Note) And [Cover/Amount For \"P1\"] > 0", NAMED_TEXT, NAMED_TEXT)
        }.let {
            RuntimeLaws.withRuleMessages(it, "/Fleet", "UnitNamed", "../Name", "UnitNamed",
                "FieldFilled(Name) And [Unit/Power For \"U1\"] > 0", UNIT_TEXT, UNIT_TEXT)
        }
        admitted(listOf(
            Triple("/Policy", "../Total", "[Cover/Amount For \"P1\"]"),
            Triple("/Fleet", "../PowerCopy", "[Unit/Power For \"U1\"]"),
            Triple("/Fleet/Trips", "../Copy", "[Leg/Fee For \"D1\"]"),
        ).foldIndexed(withRule) { i, json, (group, target, operation) ->
            RuntimeLaws.withComputation(json, group, "Comp$i", target, operation)
        })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, DOCUMENTS)
    }

    @Test
    fun aKeyedNameParameterRendersTheFieldsName() {
        val matched = DOCUMENTS.getValue("every key matches")
        DocumentDifferential.assertMessageTexts(model, matched, "CoverNamed", listOf("/Policy[1]/Note" to "Cover Amount is set"))
        DocumentDifferential.assertMessageTexts(model, matched, "UnitNamed", listOf("/Fleet[1]/Name" to "Unit Power is set"))
    }

    private companion object {
        const val NAMED_TEXT = "Cover \$Cover/Amount For \"P1\"\$ is set"
        const val UNIT_TEXT = "Unit \$Unit/Power For \"U1\"\$ is set"

        fun trip(key: String?) = """{"TripNote":"t","Leg":{${key?.let { """"LegKey":"$it",""" } ?: ""}"Fee":4}}"""

        fun document(policy: String?, unit: String?, vararg trips: String?) = """{"Policy":{""" +
            (policy?.let { """"PolicyRef":"$it",""" } ?: "") + """"Note":"n","Cover":{"Amount":7}},""" +
            """"Fleet":{"Name":"f","Unit":{""" + (unit?.let { """"UnitKey":"$it",""" } ?: "") +
            """"Power":90},"Trips":[${trips.joinToString(",") { trip(it) }}]}}"""

        val DOCUMENTS = linkedMapOf(
            "every key matches" to document("P1", "U1", "D1", "D1"),
            "every key differs" to document("P2", "U2", "D2", "D3"),
            "every index is empty" to document(null, null, null, "D1"),
        )
    }
}
