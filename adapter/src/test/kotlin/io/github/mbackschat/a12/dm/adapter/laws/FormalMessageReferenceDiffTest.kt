package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Every formal message names its own field as referenced, and an omission-typed one also names it as responsible
 * for the omission — on every formal family, because the kernel adds them all through one funnel
 * (`FormalChecker.addFormalError`, and `CalculationController.addBerechnungsFehler` for the computed-value error,
 * runtime classes). One witness per family: a date and a number format error, a missing required field, a
 * duplicate index key, both over-repetition codes, and a computation's domain failure on the operand channel.
 *
 * Own domain (online order fulfilment).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormalMessageReferenceDiffTest {
    private val model: String = run {
        val draft = DocumentModelDraft.named("order-formal-references").locale("en_US")
            .rootGroup("Order") { order ->
                order.dateField("Placed") { it.format("yyyy-MM-dd") }
                order.numberField("Quantity") { it.fractionDigits(0) }
                order.numberField("Divisor") { it.fractionDigits(0) }
                order.numberField("PerUnit") { it.fractionDigits(0) }
                order.stringField("Customer") { it.required() }
                order.group("Lines") { lines ->
                    lines.repeatable(2)
                    lines.indexField("LineNo")
                    lines.stringField("LineNo") { }
                    lines.numberField("Qty") { it.fractionDigits(0) }
                }
            }.build().toJson()
        val session = EditSession.read(draft)
        val order = session.group("/Order")
        TypedRules.inject(session.toJson(), order.computation(order.num("PerUnit")) {
            it.name("PerUnitComp")
                .calc(Dsl.roundAccounting(order.num("Quantity").asNumber().dividedBy(order.num("Divisor").asNumber()), 0).expression())
                .message(Dsl.en("Check the unit price.")).build()
        })
    }

    private val documents = linkedMapOf(
        "formats-and-omission" to """{"Order":{"Placed":"soon","Quantity":"many","Lines":[{"LineNo":"1","Qty":1}]}}""",
        "duplicate-and-over-capacity" to """{"Order":{"Placed":"2024-05-01","Quantity":4,"Customer":"Ada",""" +
            """"Lines":[{"LineNo":"1","Qty":1},{"LineNo":"1","Qty":2},{"LineNo":"3","Qty":3}]}}""",
        "domain-failure" to """{"Order":{"Placed":"2024-05-01","Quantity":4,"Divisor":0,"PerUnit":9,"Customer":"Ada",""" +
            """"Lines":[{"LineNo":"1","Qty":1}]}}""",
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun everyFormalFamilyReferencesItsOwnFieldOnAllThreeEngines() {
        DocumentDifferential.assertAgree(model, documents)
        // The positive control: each family this file claims was actually emitted, so an empty agreement cannot pass.
        val codesSeen = documents.values.flatMap { json ->
            DocumentDifferential.kernel(model, json, dynamic = true).let { it.messages + it.operandErrors }
        }.map { it.code }
        assertEquals(setOf("datumFormatFalsch", "zahlHatUngueltigeZeichen", "mandatoryField", "uniqueIndex",
            "zuGrosseZeile", "zuGrosseKontextnummer", "berechnungsWertFehler"),
            codesSeen.filter { it.first().isLowerCase() }.toSet())
    }
}
