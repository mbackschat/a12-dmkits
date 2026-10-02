package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — a once rule on an EMPTY root is unevaluated however much a SIBLING root holds.
 *
 * Found on SPEC-2026-10-02-04's root-content row: with `Insured` filled beside an empty `Claimant`, the interpreter
 * fired `Claimant`'s once rules where both kernel strategies stay silent, because it counted content under another
 * root as in scope. The two rules are both once-scoped and both decided by an empty operand — a star-only
 * `MinValue(Lines*` / `Value) < 5` and a plain `[Amount] < 5` — so they fire exactly when the root is evaluated.
 * The one-root model is the control: there the empty-root rows were already right, so a sibling root is the only
 * variable.
 *
 * Own domain (an insurance claim beside its insured party).
 */
// One instance per class: the models are fields, and this task carries timing floors (see FilteredStarCapacityExtentDiffTest).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SiblingRootContentGateDiffTest {
    private fun model(withSibling: Boolean): String {
        val draft = DocumentModelDraft.named("claim-once-root-gate").locale("en_US")
            .let { if (withSibling) it.rootGroup("Insured") { insured -> insured.stringField("Name") { } } else it }
        val base = draft.rootGroup("Claimant") { claimant ->
            claimant.stringField("Note") { }.numberField("Amount") { it.fractionDigits(0) }
                .group("Lines") { it.repeatable(3).numberField("Value") { f -> f.fractionDigits(0) } }
                .group("Sub") { sub -> sub.group("Items") { it.repeatable(3).numberField("Cost") { f -> f.fractionDigits(0) } } }
        }.build().toJson()
        val m = BoundModel.ofJson(base)
        val amount = m.num("/Claimant/Amount")
        return admitted(listOf(
            m.rule(m.group("/Claimant")).name("LinesLowRule").errorField(m.num("/Claimant/Lines/Value"))
                .`when`(Dsl.minValue(m.flatten("/Claimant/Lines*/Value")).lt(5L))
                .code("LINES_LOW").severity(Severity.ERROR).message(Dsl.en("LINES_LOW")).build(),
            m.rule(m.group("/Claimant")).name("AmountLowRule").errorField(amount).`when`(amount.asNumber().lt(5L))
                .code("AMOUNT_LOW").severity(Severity.ERROR).message(Dsl.en("AMOUNT_LOW")).build(),
            m.rule(m.group("/Claimant/Sub")).name("ItemsLowRule").errorField(m.num("/Claimant/Sub/Items/Cost"))
                .`when`(Dsl.minValue(m.flatten("/Claimant/Sub/Items*/Cost")).lt(5L))
                .code("ITEMS_LOW").severity(Severity.ERROR).message(Dsl.en("ITEMS_LOW")).build(),
        ).fold(base) { json, rule -> TypedRules.inject(json, rule) })
    }

    private val withSibling = model(withSibling = true)
    private val alone = model(withSibling = false)
    private val both = listOf("AMOUNT_LOW", "ITEMS_LOW", "LINES_LOW")

    @Test
    fun aSiblingRootsContentDoesNotEvaluateAnEmptyRoot() {
        val rows = linkedMapOf(
            """{"Insured":{"Name":"Ada"},"Claimant":{}}""" to emptyList(),
            """{"Insured":{"Name":"Ada"}}""" to emptyList(),
            """{"Insured":{"Name":"Ada"},"Claimant":{"Lines":[]}}""" to emptyList(),
            """{"Insured":{},"Claimant":{}}""" to emptyList(),
            """{}""" to emptyList(),
            """{"Insured":{"Name":"Ada"},"Claimant":{"Note":"late"}}""" to both,
        )
        assertEquals(rows.toList(), rows.keys.map { it to fired(withSibling, it) })
    }

    @Test
    fun theOneRootControlAgrees() {
        val rows = linkedMapOf(
            """{"Claimant":{}}""" to emptyList(),
            """{}""" to emptyList(),
            """{"Claimant":{"Lines":[]}}""" to emptyList(),
            """{"Claimant":{"Note":"late"}}""" to both,
        )
        assertEquals(rows.toList(), rows.keys.map { it to fired(alone, it) })
    }
}
