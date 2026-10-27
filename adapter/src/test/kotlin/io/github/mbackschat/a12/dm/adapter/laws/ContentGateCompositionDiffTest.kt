package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Tolerance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether a document with no content opens a rule's content gate, on the compositions a12-kernel-lean
 * SPEC-2026-10-26-23 named ([KERNEL-FINDINGS KF632]). A conjunction of two absence leaves fires on an empty
 * document and on an empty root alike, and a tolerance comparison fires there whenever either side counts filled
 * fields, on either side; the same comparison over a plain field sum does not. Every row is three-engine agreed.
 *
 * Own domain (a customer's contact details).
 */
class ContentGateCompositionDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("customer-contact-gate").locale("en_US")
            .rootGroup("Customer") { c -> c.stringField("Email") { }.stringField("Note") { }
                .numberField("Qty") { it.fractionDigits(0) }.numberField("W") { it.fractionDigits(0) } }
            .build().toJson()
        val session = EditSession.read(base)
        val customer = session.group("/Customer")
        val email = customer.field("Email")
        val note = customer.field("Note")
        val w = customer.num("W").asNumber()
        val count = Dsl.numberOfFilledFields(listOf(email, note)).plus(Dsl.number(5))
        val rules: List<Triple<String, String, Condition>> = listOf(
            Triple("AndContentFires", "Email", customer.str("Email").eq("e").and(note.notFilled())),
            Triple("AndFiresFires", "Email", email.notFilled().and(note.notFilled())),
            Triple("TolCountRight", "W", w.differsWithTolerance(Tolerance.RANGE_1, count)),
            Triple("TolCountLeft", "W", count.differsWithTolerance(Tolerance.RANGE_1, w)),
            Triple("TolNoCount", "W", w.differsWithTolerance(Tolerance.RANGE_1,
                customer.num("Qty").asNumber().plus(Dsl.number(5)))),
        )
        for ((code, error, condition) in rules) customer.addRule {
            it.name(code).code(code).errorField(customer.field(error))
                .`when`(condition).message(Dsl.en("Check the contact details.")).build()
        }
        admitted(session.toJson())
    }

    @Test
    fun anEmptyDocumentOpensTheGatesA12Opens() {
        val observed = listOf("{}", """{"Customer":{}}""", """{"Customer":{"Email":"e"}}""")
            .associateWith { fired(model, it) }
        assertEquals(linkedMapOf(
            "{}" to listOf("AndFiresFires", "TolCountLeft", "TolCountRight"),
            """{"Customer":{}}""" to listOf("AndFiresFires", "TolCountLeft", "TolCountRight"),
            """{"Customer":{"Email":"e"}}""" to listOf("AndContentFires", "TolCountLeft", "TolCountRight", "TolNoCount"),
        ), observed)
    }
}
