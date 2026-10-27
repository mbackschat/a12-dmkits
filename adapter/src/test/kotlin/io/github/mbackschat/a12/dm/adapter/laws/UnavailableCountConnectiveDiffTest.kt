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
 * A malformed cell makes an all-cells count UNKNOWN as an operand, not the rule: `Or` beside a true comparison
 * still fires and `And` does not, while `FieldValuesNotUnique` skips the cell and fires on a duplicate among
 * the others ([KERNEL-FINDINGS KF629], a12-kernel-lean SPEC-2026-10-26-01). Every row is three-engine agreed:
 * dynamic Groovy, static Java and the interpreter.
 *
 * Own domain (a shopping cart).
 */
class UnavailableCountConnectiveDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("cart-unavailable-count").locale("en_US")
            .rootGroup("Cart") { c -> c.stringField("Ref") { }
                .group("Lines") { l -> l.repeatable(3).numberField("Qty") { it.fractionDigits(0) } } }
            .build().toJson()
        val session = EditSession.read(base)
        val cart = session.group("/Cart")
        val qty = cart.flatten("Lines*/Qty")
        val ref = cart.str("Ref").eq("X")
        val rules: List<Pair<String, Condition>> = listOf(
            "OrFilled" to Dsl.numberOfFilledFields(qty).gt(0).or(ref),
            "AndFilled" to Dsl.numberOfFilledFields(qty).ge(0).and(ref),
            "OrDistinct" to Dsl.numberOfDifferentValues(qty).gt(0).or(ref),
            "AndDistinct" to Dsl.numberOfDifferentValues(qty).ge(0).and(ref),
            "NotUnique" to Dsl.fieldValuesNotUnique(qty).and(cart.str("Ref").filled()),
        )
        for ((code, condition) in rules) cart.addRule {
            it.name(code).code(code).errorField(cart.field("Ref"))
                .`when`(condition).message(Dsl.en("Check the cart.")).build()
        }
        admitted(session.toJson())
    }

    @Test
    fun aMalformedCellLeavesItsCountUnknownAndTheConnectiveDecides() {
        val observed = linkedMapOf(
            "malformed, ref X" to """{"Cart":{"Ref":"X","Lines":[{"Qty":"abc"},{"Qty":2}]}}""",
            "malformed, ref Y" to """{"Cart":{"Ref":"Y","Lines":[{"Qty":"abc"},{"Qty":2}]}}""",
            "clean, ref X" to """{"Cart":{"Ref":"X","Lines":[{"Qty":1},{"Qty":2}]}}""",
            "malformed beside a duplicate" to """{"Cart":{"Ref":"Y","Lines":[{"Qty":"abc"},{"Qty":2},{"Qty":2}]}}""",
        ).mapValues { (_, document) -> fired(model, document) }
        assertEquals(linkedMapOf(
            // `zahlHatUngueltigeZeichen` is the malformed cell's own formal error.
            "malformed, ref X" to listOf("OrDistinct", "OrFilled", "zahlHatUngueltigeZeichen"),
            "malformed, ref Y" to listOf("zahlHatUngueltigeZeichen"),
            "clean, ref X" to listOf("AndDistinct", "AndFilled", "OrDistinct", "OrFilled"),
            "malformed beside a duplicate" to listOf("NotUnique", "zahlHatUngueltigeZeichen"),
        ), observed)
    }
}
