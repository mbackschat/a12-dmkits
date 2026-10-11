package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC-2026-10-10-14: an empty nested prefix contributes omission even without a physical child row. */
class FirstFilledNestedPrefixDiffTest {
    private enum class Carrier(val kind: FieldKind, val value: String) {
        TEXT(FieldKind.STRING, "\"ready\""), NUMBER(FieldKind.NUMBER, "7"), BOOLEAN(FieldKind.BOOLEAN, "true")
    }

    private fun model(carrier: Carrier): String {
        val base = DocumentModelDraft.named("claim-first-payout").locale("en_US")
            .rootGroup("Claim") { c ->
                c.stringField("Ref") { }
                c.group("Breakdown") { b -> b.group("Payouts") { p -> p.repeatable(2).group("Parts") { part ->
                    part.repeatable(2).field("Value", carrier.kind)
                } } }
            }.build().toJson()
        val session = EditSession.read(base)
        val claim = session.group("/Claim")
        val bound = BoundModel.ofJson(base)
        var json = session.toJson()
        for ((name, operand) in linkedMapOf(
            "FieldStar" to claim.flatten("Breakdown/Payouts*/Parts*/Value").firstFilledOperand(),
            "Group" to bound.firstFilledGroup("/Claim/Breakdown"),
            "GroupStar" to bound.firstFilledGroup("/Claim/Breakdown/Payouts*/Parts*"),
        )) {
            val condition = when (carrier) {
                Carrier.TEXT -> Dsl.firstFilledString(operand).eq("ready")
                Carrier.NUMBER -> Dsl.firstFilledNumber(operand).eq(7)
                Carrier.BOOLEAN -> Dsl.firstFilledBoolean(operand).isTrue()
            }
            json = TypedRules.inject(json, claim.rule {
                it.name(name + "Rule").code(name).errorField(claim.str("Ref").field())
                    .`when`(claim.str("Ref").filled().and(condition))
                    .message(Dsl.en("Check the first payout.")).build()
            })
        }
        return json
    }

    @Test
    fun absentEmptyAndPresentEmptyPrefixesAgreeAcrossKindsAndGroupCarriers() {
        for (carrier in Carrier.entries) {
            val json = model(carrier)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
            val filled = """{"Parts":[{"Value":${carrier.value}}]}"""
            val documents = linkedMapOf(
                "omitted prefix" to """{"Claim":{"Ref":"C","Breakdown":{"Payouts":[{},$filled]}}}""",
                "empty prefix" to """{"Claim":{"Ref":"C","Breakdown":{"Payouts":[{"Parts":[]},$filled]}}}""",
                "present-empty prefix" to """{"Claim":{"Ref":"C","Breakdown":{"Payouts":[{"Parts":[{}]},$filled]}}}""",
                "omitted suffix" to """{"Claim":{"Ref":"C","Breakdown":{"Payouts":[$filled,{}]}}}""",
            )
            for ((name, document) in documents) {
                val expected = DocumentDifferential.kernel(json, document, dynamic = true)
                assertEquals(3, expected.messages.size, "$carrier $name must exercise every carrier")
                assertEquals(setOf(if (name == "omitted suffix") "VALUE_ERROR" else "OMISSION_ERROR"),
                    expected.messages.map { it.type }.toSet(), "$carrier $name")
            }
            DocumentDifferential.assertAgree(json, documents)
        }
    }
    @Test
    fun keyedNestedScopesPreservePrefixPolarityAndUnavailableSelection() {
        for (carrier in Carrier.entries) {
            fun branch(group: DocumentModelDraft.GroupBuilder) {
                group.group("Payouts") { payouts -> payouts.repeatable(2).group("Parts") { parts ->
                    parts.repeatable(2).field("Value", carrier.kind)
                } }
            }
            val base = DocumentModelDraft.named("claim-selected-payout").locale("en_US")
                .rootGroup("Claim") { claim ->
                    claim.stringField("Ref") { }.stringField("SelectedPolicy") { }
                    claim.group("Breakdown") { branch(it) }
                    claim.group("Policies") { policy ->
                        policy.repeatable(3).indexField("Code").stringField("Code") { }
                        policy.group("Breakdown") { branch(it) }
                    }
                }.build().toJson()
            val edit = EditSession.read(base)
            val claim = edit.group("/Claim")
            val ref = claim.str("Ref")
            val key = claim.str("SelectedPolicy").field()
            val bound = BoundModel.ofJson(base)
            val operands = linkedMapOf(
                "Ordinary" to bound.firstFilledGroup("/Claim/Breakdown"),
                "Starred" to bound.firstFilledGroup("/Claim/Breakdown/Payouts*/Parts*"),
                "KeyedField" to claim.flatten("Policies/Breakdown/Payouts*/Parts*/Value").firstFilledOperand().forIndex(key),
                "KeyedGroup" to bound.firstFilledGroupFor("/Claim/Policies/Breakdown", key),
                "KeyedStarred" to bound.firstFilledGroupFor("/Claim/Policies/Breakdown/Payouts*/Parts*", key),
            )
            for ((name, operand) in operands) {
                val selected = when (carrier) {
                    Carrier.TEXT -> Dsl.firstFilledString(operand).eq("ready")
                    Carrier.NUMBER -> Dsl.firstFilledNumber(operand).eq(7)
                    Carrier.BOOLEAN -> Dsl.firstFilledBoolean(operand).isTrue()
                }
                claim.addRule { it.name(name + "Payout").code(name).errorField(ref)
                    .`when`(ref.filled().and(selected)).message(Dsl.en("Review the selected payout.")).build() }
            }
            val model = edit.toJson()
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
            val filled = """{"Parts":[{"Value":${carrier.value}}]}"""
            val mismatch = when (carrier) { Carrier.TEXT -> "\"pending\""; Carrier.NUMBER -> "3"; Carrier.BOOLEAN -> "false" }
            fun document(rows: String, selected: String = "match", duplicate: Boolean = false): String {
                val policy = """{"Code":"match","Breakdown":{"Payouts":$rows}}"""
                return """{"Claim":{"Ref":"C","SelectedPolicy":"$selected","Breakdown":{"Payouts":$rows},"Policies":[$policy${if (duplicate) ",$policy" else ""}]}}"""
            }
            val scenarios = linkedMapOf(
                "omitted prefix" to ("[{},$filled]" to "OMISSION_ERROR"),
                "empty-array prefix" to ("""[{"Parts":[]},$filled]""" to "OMISSION_ERROR"),
                "empty-cell prefix" to ("""[{"Parts":[{}]},$filled]""" to "OMISSION_ERROR"),
                "omitted suffix" to ("[$filled,{}]" to "VALUE_ERROR"),
                "empty-array suffix" to ("""[$filled,{"Parts":[]}]""" to "VALUE_ERROR"),
                "empty-cell suffix" to ("""[$filled,{"Parts":[{}]}]""" to "VALUE_ERROR"),
                "filled" to ("[$filled]" to "VALUE_ERROR"),
                "all empty" to ("[{},{}]" to null),
                "nonmatching first" to ("""[{"Parts":[{"Value":$mismatch}]},$filled]""" to null),
            )
            val documents = linkedMapOf<String, String>()
            for ((name, scenario) in scenarios) {
                val json = document(scenario.first)
                documents[name] = json
                val expected = DocumentDifferential.kernel(model, json, dynamic = true).messages
                assertEquals(if (scenario.second == null) emptyList() else operands.keys.sorted(), expected.map { it.code }.sorted(), "$carrier $name")
                assertEquals(if (scenario.second == null) emptySet() else setOf(scenario.second), expected.map { it.type }.toSet(), "$carrier $name")
            }
            for (duplicate in listOf(false, true)) {
                val name = if (duplicate) "duplicate key" else "absent key"
                val json = document("[$filled]", if (duplicate) "match" else "absent", duplicate)
                documents[name] = json
                val expected = DocumentDifferential.kernel(model, json, dynamic = true).messages
                assertEquals(listOf("Ordinary", "Starred"), expected.filter { it.code in operands }.map { it.code }.sorted(), "$carrier $name")
                assertEquals(if (duplicate) 2 else 0, expected.count { it.code == "uniqueIndex" }, "$carrier $name")
            }
            DocumentDifferential.assertAgree(model, documents)
        }
    }

}
