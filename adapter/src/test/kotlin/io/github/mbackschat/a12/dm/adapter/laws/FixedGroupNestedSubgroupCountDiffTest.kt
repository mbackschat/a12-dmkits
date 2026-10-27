package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An unstarred fixed group operand of `NumberOfFilledFields` and `NumberOfValueInFields` expands to every field in
 * its subtree, a nested non-repeatable subgroup included ([KERNEL-FINDINGS KF641], a12-kernel-lean
 * SPEC-2026-10-26-03). The discriminator is a value held only in the nested `Address` group: a direct-fields
 * expansion counts nothing there, a recursive one counts it. The `Code` row is the control both accounts agree on,
 * and the empty row shows the zero rules can fire.
 *
 * Own domain (an order's billing details).
 */
class FixedGroupNestedSubgroupCountDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("order-billing-nested-count").locale("en_US")
            .rootGroup("Order") { o -> o.stringField("Ref") { }
                .group("Billing") { b -> b.stringField("Code") { }
                    .group("Address") { a -> a.stringField("Street") { } } } }
            .build().toJson()
        val session = EditSession.read(base)
        val order = session.group("/Order")
        val billing = order.flattenGroup("Billing")
        val rules: List<Pair<String, Condition>> = listOf(
            "Filled1" to Dsl.numberOfFilledFields(billing).eq(1),
            "Filled0" to Dsl.numberOfFilledFields(billing).eq(0),
            "ValueX1" to Dsl.numberOfValueInFields("X", billing).eq(1),
            "ValueX0" to Dsl.numberOfValueInFields("X", billing).eq(0),
        )
        for ((code, condition) in rules) order.addRule {
            it.name(code).code(code).errorField(order.field("Ref"))
                .`when`(order.str("Ref").filled().and(condition)).message(Dsl.en("Check the billing details.")).build()
        }
        admitted(session.toJson())
    }

    @Test
    fun aFieldOnlyInTheNestedSubgroupIsCounted() {
        val observed = linkedMapOf(
            "street only" to """{"Order":{"Ref":"R","Billing":{"Address":{"Street":"X"}}}}""",
            "code only" to """{"Order":{"Ref":"R","Billing":{"Code":"X"}}}""",
            "nothing" to """{"Order":{"Ref":"R"}}""",
        ).mapValues { (_, document) -> fired(model, document) }
        assertEquals(mapOf(
            "street only" to listOf("Filled1", "ValueX1"),
            "code only" to listOf("Filled1", "ValueX1"),
            "nothing" to listOf("Filled0", "ValueX0"),
        ), observed)
    }
}
