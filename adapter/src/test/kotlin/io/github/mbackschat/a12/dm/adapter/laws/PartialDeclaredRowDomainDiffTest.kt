package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which rows an authored rule runs on under partial validation: every declared slot the selection covers, a slot no
 * row holds included (KERNEL-FINDINGS KF553). Full validation runs it only where content is, so the two differ
 * exactly on the rows the document lacks. A coordinate of 0 is a wildcard.
 *
 * Own domain (a lease and its rented units, each with a fixed inspection score).
 */
class PartialDeclaredRowDomainDiffTest {

    private val model: String = lease(capacity = 3)

    private fun lease(capacity: Int): String = run {
        val session = EditSession.read(DocumentModelDraft.named("lease-unit-domain").locale("en_US")
            .rootGroup("Lease") { l -> l.stringField("Tenant") { }
                .group("Units") { u -> u.repeatable(capacity).numberField("Rent") { it.fractionDigits(0) }
                    .group("Inspection") { it.numberField("Score") { n -> n.fractionDigits(0) } } } }
            .build().toJson())
        val units = session.group("/Lease/Units")
        units.addRule {
            it.name("RentFloor").code("RENT_FLOOR").errorField(units.num("Rent"))
                .`when`(units.num("Rent").lt(100)).message(Dsl.en("The rent is below the floor.")).build()
        }
        val inspection = session.group("/Lease/Units/Inspection")
        inspection.addRule {
            it.name("ScoreFloor").code("SCORE_FLOOR").errorField(inspection.num("Score"))
                .`when`(inspection.num("Score").lt(3)).message(Dsl.en("The inspection score is below the floor.")).build()
        }
        session.toJson()
    }

    private val documents = linkedMapOf(
        "no units" to """{"Lease":{"Tenant":"T"}}""",
        "one low unit" to """{"Lease":{"Tenant":"T","Units":[{"Rent":50,"Inspection":{"Score":5}}]}}""",
        "a fourth unit past capacity" to """{"Lease":{"Tenant":"T","Units":[{"Rent":500},{"Rent":500},{"Rent":500},{"Rent":50}]}}""",
    )

    private val selections = linkedMapOf(
        "every unit's rent" to setOf(RelevantEntity.anyRep("/Lease/Units/Rent")),
        "the second unit's rent" to setOf(RelevantEntity("/Lease/Units/Rent", listOf(0, 2, 0))),
        "every score" to setOf(RelevantEntity.anyRep("/Lease/Units/Inspection/Score")),
        "the tenant" to setOf(RelevantEntity("/Lease/Tenant", listOf(0, 0))),
        "the lease" to setOf(RelevantEntity("/Lease", listOf(0))),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun fullValidationAgrees() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** Every selection on every document: both strategies and the interpreter agree, on the pinned findings. */
    @Test
    fun partialValidationRunsEveryDeclaredSlotTheSelectionCovers() {
        val rent = (1..3).map { "RENT_FLOOR@Units[$it]/Rent" }
        val score = (1..3).map { "SCORE_FLOOR@Units[$it]/Inspection[1]/Score" }
        val excess = listOf("zuGrosseKontextnummer@Units[4]/Rent", "zuGrosseZeile@Units")
        val expected = mapOf(
            "no units" to mapOf("every unit's rent" to rent, "the second unit's rent" to listOf(rent[1]),
                "every score" to score, "the tenant" to emptyList(), "the lease" to rent + score),
            // The held first unit scores 5; its rent of 50 is reported only where it is selected.
            "one low unit" to mapOf("every unit's rent" to rent, "the second unit's rent" to listOf(rent[1]),
                "every score" to score.drop(1), "the tenant" to emptyList(), "the lease" to rent + score.drop(1)),
            // Past the capacity, the fourth unit's rent of 50 is never judged; the held units' rent of 500 passes.
            "a fourth unit past capacity" to mapOf("every unit's rent" to excess, "the second unit's rent" to excess,
                "every score" to score + excess, "the tenant" to excess, "the lease" to score + excess),
        )
        for ((document, json) in documents) for ((label, selected) in selections) {
            val dynamic = DocumentDifferential.kernelMessages(model, json, true, selected)
            assertEquals(dynamic, DocumentDifferential.kernelMessages(model, json, false, selected), "$document / $label: static")
            assertEquals(dynamic, DocumentDifferential.interpreterMessages(model, json, selected), "$document / $label: interpreter")
            assertEquals(expected.getValue(document).getValue(label).sorted(),
                dynamic.map { "${it.code}@${it.pointer.removePrefix("/Lease[1]/")}" }.sorted(), "$document / $label")
        }
    }

    /**
     * Above A12's dense iteration limit, a capacity of eight, the selection covers only the slots full validation
     * would walk: the first unit and every unit the document holds, so a selected fifth slot runs nothing.
     */
    @Test
    fun aSparseModelRunsOnlyTheSlotsFullValidationWalks() {
        val sparse = lease(capacity = 8)
        val sparseDocuments = linkedMapOf("no units" to documents.getValue("no units"),
            "units one and three at rent 500" to """{"Lease":{"Tenant":"T","Units":[{"Rent":500},{},{"Rent":500}]}}""")
        val sparseSelections = linkedMapOf("every unit's rent" to setOf(RelevantEntity.anyRep("/Lease/Units/Rent")),
            "the fifth unit's rent" to setOf(RelevantEntity("/Lease/Units/Rent", listOf(0, 5, 0))))
        val actual = sparseDocuments.mapValues { (_, json) -> sparseSelections.mapValues { (_, selected) ->
            val dynamic = DocumentDifferential.kernelMessages(sparse, json, true, selected)
            assertEquals(dynamic, DocumentDifferential.kernelMessages(sparse, json, false, selected), "static")
            assertEquals(dynamic, DocumentDifferential.interpreterMessages(sparse, json, selected), "interpreter")
            dynamic.map { "${it.code}@${it.pointer.removePrefix("/Lease[1]/")}" }.sorted()
        } }
        assertEquals(mapOf(
            "no units" to mapOf("every unit's rent" to listOf("RENT_FLOOR@Units[1]/Rent"), "the fifth unit's rent" to emptyList()),
            // The empty second unit fires; the fifth slot, which no held unit's coordinate reaches, never runs.
            "units one and three at rent 500" to
                mapOf("every unit's rent" to listOf("RENT_FLOOR@Units[2]/Rent"), "the fifth unit's rent" to emptyList()),
        ), actual)
    }
}
