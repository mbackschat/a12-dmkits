package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.signatures
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential — **`FieldValuesNotUnique` over the rule's OWN repeatable group, written unstarred or as
 * `RuleGroup`, compares the cells of the rule's row alone** (KERNEL-FINDINGS KF624, a12-kernel-lean
 * SPEC-2026-10-26-14).
 *
 * The rule iterates `Lines` and reports on `Sku`. Its operand expands to the row's `Sku` and `Alt`, so a duplicate
 * inside one row fires at that row, while equal values in two different rows fire nothing. The starred `Lines*` is
 * the control: it compares the whole column, so the same across-rows document fires it. The interpreter once
 * compared the whole column on all three spellings and fired at every declared row, the absent third one included,
 * because its expansion left the operand's own level unbound.
 *
 * Own domain (an order whose lines carry a product code and an alternative code).
 */
class FieldValuesNotUniqueOwnRowOperandDiffTest {

    private val base = DocumentModelDraft.named("order-line-codes").locale("en_US")
        .rootGroup("Order") { o -> o.stringField("Ref") { }
            .group("Lines") { l -> l.repeatable(3).stringField("Sku") { }.stringField("Alt") { } } }
        .build().toJson()

    private fun withRule(json: String, code: String, condition: String) =
        RuntimeLaws.withRule(json, "/Order/Lines", "${code}Rule", "../Sku", code, condition)

    /** A12's verdict on each spelling, and the portable checker's beside it. */
    @Test
    fun theRulesOwnGroupIsAdmittedUnstarredAndAsRuleGroup() {
        fun checker(json: String) = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }
        val admittedForms = listOf("FieldValuesNotUnique(/Order/Lines)", "FieldValuesNotUnique(RuleGroup)",
            "FieldValuesNotUnique(/Order/Lines*)")
        for (condition in admittedForms) {
            val json = withRule(base, "U", condition)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json), condition)
            assertEquals(emptyList<String>(), checker(json), "checker — $condition")
        }
        // The relative spelling names no group from inside `Lines`: the control that the rows above are admissions.
        val relative = withRule(base, "U", "FieldValuesNotUnique(Lines)")
        assertEquals(listOf("ERROR: Rule with path '/Order/Lines/URule': The group or field 'Lines' is not valid. [MVK_INVALID_ENTITY]"),
            RuntimeLaws.consistencyMessages(relative))
        assertEquals(listOf("MVK_INVALID_ENTITY"), checker(relative), "checker — the relative spelling")
    }

    @Test
    fun theUnstarredOwnGroupComparesTheRulesRowAlone() {
        val model = admitted(listOf(
            "Unstarred" to "FieldValuesNotUnique(/Order/Lines)",
            "RuleGroup" to "FieldValuesNotUnique(RuleGroup)",
            "Starred" to "FieldValuesNotUnique(/Order/Lines*)",
        ).fold(base) { json, (code, condition) -> withRule(json, code, condition) })
        fun at(code: String) = "$code|VALUE_ERROR|/Order[1]/Lines[1]/Sku"
        assertEquals(listOf(at("RuleGroup"), at("Starred"), at("Unstarred")),
            signatures(model, """{"Order":{"Ref":"R","Lines":[{"Sku":"A","Alt":"A"},{"Sku":"B","Alt":"C"}]}}"""),
            "a duplicate inside the first row")
        assertEquals(listOf(at("Starred")),
            signatures(model, """{"Order":{"Ref":"R","Lines":[{"Sku":"A","Alt":"B"},{"Sku":"C","Alt":"A"}]}}"""),
            "equal values in two rows: only the starred column compares them")
        assertEquals(emptyList<String>(),
            signatures(model, """{"Order":{"Ref":"R","Lines":[{"Sku":"A","Alt":"B"},{"Sku":"C","Alt":"D"}]}}"""),
            "no duplicate anywhere")
    }
}
