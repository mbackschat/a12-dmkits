package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * SPEC-2026-10-03-05: `AtLeastOneGroupFilled` over a sole root group keeps the special dependency treatment
 * `GroupFilled` over a containing group has. Two computations in one root, each guarded by that root form, are admitted
 * together, so neither guard is a read of the other's target; an ordinary group read would make them a cycle. They then
 * compute from the root's other content alone: both clear on a document holding only their stored values, and both
 * compute once an input field is present.
 *
 * Own domain (e-commerce checkout). Targets are seeded with a value neither alternative produces.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RootGroupGuardDependencyDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("checkout-root-guard").locale("en_US")
            .rootGroup("Checkout") { c ->
                c.numberField("Voucher") { it.fractionDigits(0) }
                c.numberField("Shipping") { it.fractionDigits(0) }
                c.numberField("Handling") { it.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft)
        val c = session.group("/Checkout")
        val guard: Condition = Dsl.atLeastOneGroupFilled(c.ref())
        var json = session.toJson()
        for ((name, value) in listOf("Shipping" to "1", "Handling" to "2")) {
            json = TypedRules.inject(json, c.computation(c.num(name)) {
                it.name("${name}Comp").`when`(guard).calc(NumberLiteral.of(value)).message(Dsl.en("Check the checkout.")).build()
            })
        }
        json
    }

    private val documents = linkedMapOf(
        "stored values only" to """{"Checkout":{"Shipping":"9","Handling":"9"}}""",
        "an input present" to """{"Checkout":{"Voucher":"3","Shipping":"9","Handling":"9"}}""",
    )

    @Test
    fun bothGuardedComputationsAreAdmittedTogether() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** The separating rows, pinned against dynamic Groovy: the root's own computed content does not fill it. */
    @Test
    fun theGuardsReadOnlyTheRootsOtherContent() {
        fun outcomes(doc: String) = DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true).outcomes
        assertEquals(linkedMapOf(
            "stored values only" to mapOf("Handling" to "CLEARED", "Shipping" to "CLEARED"),
            "an input present" to mapOf("Handling" to "VALUE|2", "Shipping" to "VALUE|1"),
        ), documents.keys.associateWith(::outcomes))
    }
}
