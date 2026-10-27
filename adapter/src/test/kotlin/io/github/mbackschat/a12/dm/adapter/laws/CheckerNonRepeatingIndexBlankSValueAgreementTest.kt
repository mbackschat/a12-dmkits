package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether the two readings of a non-repeating index terminal agree on a blank S-value, the acceptance of
 * a12-kernel-lean SPEC-2026-10-26-19 ([KERNEL-FINDINGS KF642]). They do: the value reading and the category
 * reading are both refused `INVALID_INDEX_FIELD_NONREP_ENUM` over the empty S-value, and both admitted over the
 * declared value and over one space. The expected column is A12's consistency check; the checker must name the
 * same class.
 *
 * Own domain (a shipment approval).
 */
class CheckerNonRepeatingIndexBlankSValueAgreementTest {

    private val declared = DocumentModelDraft.named("shipment-approval").locale("en_US")
        .rootGroup("Shipment") { s -> s.group("Approval") { a -> a.indexField("Status")
            .enumField("Status") { e -> e.value("APPROVED").category("Category", "FINAL").sValue("APPROVED") }
            .stringField("Note") { } } }
        .build().toJson()

    /**
     * The model with its S-value respelled in the JSON text, because the builder refuses a blank one on purpose
     * (`EnumFieldBuilder.sValue`) while A12 reads it.
     */
    private fun shipment(sValue: String): String =
        declared.replaceFirst(Regex("\"sValue\"\\s*:\\s*\"APPROVED\""), "\"sValue\":\"$sValue\"").also {
            check("\"sValue\":\"$sValue\"" in it) { "the S-value edit missed" }
        }

    private fun withMessage(model: String, message: String): String {
        val approval = EditSession.read(model).group("/Shipment/Approval")
        val note = approval.field("Note")
        return ModelWriter.addRule(model, approval.rule {
            it.name("ApprovalNote").code("NOTE").errorField(note)
                .`when`(approval.field("Status").filled().and(note.notFilled())).message(Dsl.en(message)).build()
        })
    }

    @Test
    fun bothReadingsOfTheIndexTerminalAgreeOnABlankSValue() {
        val readings = listOf("\$index(Status).value\$", "\$index(Status)->Category\$")
        val observed = linkedMapOf<String, Pair<List<String>, List<String>>>()
        for (sValue in listOf("APPROVED", "", " ")) {
            for (reading in readings) {
                val model = withMessage(shipment(sValue), "Approval $reading needs a note.")
                observed["[$sValue] $reading"] = MessageParameterProbes.kernel(model) to MessageParameterProbes.portable(model)
            }
        }
        val refused = listOf("INVALID_INDEX_FIELD_NONREP_ENUM")
        val expected = observed.keys.associateWith { key ->
            (if (key.startsWith("[]")) refused else emptyList()).let { it to it }
        }
        assertEquals(expected, observed, "as (A12's classes, the checker's)")
    }
}
