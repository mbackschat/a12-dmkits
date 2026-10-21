package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which written entities an over-repetition reaches, on each channel (KERNEL-FINDINGS KF58, a12-kernel-lean
 * SPEC-2026-10-13-05). Every written entity is judged on its own ancestry: a sibling group's third row shares the
 * excess row's leading coordinate and stays clean within its own capacity. A nonempty partial selection reports the
 * full set and an empty one none. The computation channel reports the uncomputed operand cells and every ancestor
 * group instance of an operand or a computed field, so a nonrepeatable group on the operand path is reported, the
 * excess row's unread fields are not, and an excess row holding only a computed target reports its groups but not
 * the target.
 *
 * Own domain (a shipment's packed rows, notes and loading bays).
 */
class OverRepetitionOccurrenceScopeDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("shipment-occurrence-scope").locale("en_US")
            .rootGroup("Shipment") { s -> s
                .stringField("Marker") { }.numberField("Total") { it.fractionDigits(0) }
                .group("Rows") { r -> r.repeatable(2).numberField("Count") { it.fractionDigits(0) }
                    .stringField("Unread") { }.group("Detail") { it.numberField("Weight") { n -> n.fractionDigits(0) } } }
                .group("Notes") { it.repeatable(5).stringField("Memo") { } }
                .group("Bays") { b -> b.repeatable(5).group("Fix") { it.numberField("Mark") { n -> n.fractionDigits(0) } } } }
            .build().toJson())
        val shipment = session.group("/Shipment")
        shipment.addComputation(shipment.num("Total")) {
            it.name("SumWeight").calc(Dsl.sum(shipment.flatten("Rows*/Detail/Weight")))
                .message(Dsl.en("Check the shipment weight.")).build()
        }
        val fix = session.group("/Shipment/Bays/Fix")
        fix.addComputation(fix.num("Mark")) {
            it.name("MarkBay").calc(Dsl.number(1)).message(Dsl.en("Check the bay mark.")).build()
        }
        session.toJson()
    }

    /** Two in-capacity rows, the first weighing 5, then [excess] as a third row when given. */
    private fun shipment(excess: String?, notes: String = "", bays: String = "") =
        """{"Shipment":{"Marker":"ready","Rows":[""" + listOfNotNull("""{"Detail":{"Weight":5}}""", "{}", excess)
            .joinToString(",") + "]$notes$bays}}"

    private val documents = linkedMapOf(
        "an excess row holding only its nonrepeatable group" to shipment("""{"Detail":{}}"""),
        "an excess row with null and empty cells" to
            shipment("""{"Count":null,"Unread":"","Detail":{"Weight":null}}"""),
        "a malformed cell in the excess row" to shipment("""{"Detail":{"Weight":"bad"}}"""),
        "siblings sharing the excess row's coordinate" to shipment("""{"Detail":{}}""",
            notes = ""","Notes":[{},{},{"Memo":"m"}]""", bays = ""","Bays":[{},{},{"Fix":{}}]"""),
        "an excess bay holding a computed target" to shipment(null,
            bays = ""","Bays":[{},{},{},{},{},{"Fix":{"Mark":"1"}}]"""),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** Full validation: one finding per written entity in the excess row, and none for an in-capacity sibling. */
    @Test
    fun everyWrittenEntityIsJudgedOnItsOwnAncestry() {
        fun findings(document: String) = DocumentDifferential.kernelMessages(model, documents.getValue(document), true)
            .map { "${it.code}@${it.pointer.removePrefix("/Shipment[1]/")}" }
        val excessRow = "zuGrosseKontextnummer@Rows[3]/Detail"
        val rowCapacity = "zuGrosseZeile@Rows"
        assertEquals(listOf(excessRow, rowCapacity), findings("an excess row holding only its nonrepeatable group"))
        assertEquals(listOf("zuGrosseKontextnummer@Rows[3]/Count", "zuGrosseKontextnummer@Rows[3]/Detail[1]/Weight", excessRow,
            "zuGrosseKontextnummer@Rows[3]/Unread", rowCapacity), findings("an excess row with null and empty cells"))
        // The third note and the third bay share the excess row's leading coordinate and stay clean.
        assertEquals(listOf(excessRow, rowCapacity), findings("siblings sharing the excess row's coordinate"))
        assertEquals(listOf("zuGrosseKontextnummer@Bays[6]/Fix[1]/Mark", "zuGrosseKontextnummer@Bays[6]/Fix", "zuGrosseZeile@Bays"),
            findings("an excess bay holding a computed target"), "validation also names the computed target")
        assertEquals(DocumentDifferential.kernelMessages(model, documents.getValue("a malformed cell in the excess row"), true)
            .map { it.code }.toSet(), setOf("zuGrosseZeile", "zuGrosseKontextnummer"))
    }

    /** A nonempty selection, even of an unrelated field, reports the full set; an empty one reports nothing. */
    @Test
    fun aNonemptySelectionReportsTheFullSet() {
        val input = documents.getValue("an excess row with null and empty cells")
        val full = DocumentDifferential.kernelMessages(model, input, true)
        val selections = linkedMapOf(
            "empty" to emptySet(),
            "the root" to setOf(RelevantEntity("/Shipment", listOf(0))),
            "the marker" to setOf(RelevantEntity("/Shipment/Marker", listOf(0, 0))),
            "an unrelated sibling field" to setOf(RelevantEntity.anyRep("/Shipment/Notes/Memo")),
        )
        for ((label, selected) in selections) {
            val dynamic = DocumentDifferential.kernelMessages(model, input, true, selected)
            assertEquals(if (selected.isEmpty()) emptyList() else full, dynamic, "$label: dynamic")
            assertEquals(dynamic, DocumentDifferential.kernelMessages(model, input, false, selected), "$label: static")
            assertEquals(dynamic, DocumentDifferential.interpreterMessages(model, input, selected), "$label: interpreter")
        }
    }

    /** The operand channel: the operand path's groups and cells, and a computed target's ancestors but not the target. */
    @Test
    fun theComputationChannelIsTheOperandInventory() {
        fun operands(document: String) = DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true)
            .operandErrors.map { it.pointer.replace(Regex("""\[\d+]"""), "") }
        assertEquals(listOf("/Shipment/Rows", "/Shipment/Rows/Detail", "/Shipment/Rows/Detail/Weight"),
            operands("an excess row with null and empty cells").sorted())
        assertEquals(listOf("/Shipment/Bays", "/Shipment/Bays/Fix"), operands("an excess bay holding a computed target").sorted())
        assertEquals("VALUE|5", DocumentDifferential.kernel(model, documents.getValue("an excess row with null and empty cells"),
            dynamic = true).outcomes.getValue("Total"))
    }
}
