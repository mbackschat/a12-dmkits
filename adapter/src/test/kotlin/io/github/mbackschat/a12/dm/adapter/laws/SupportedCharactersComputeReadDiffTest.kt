package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A stored value outside the model's `supportedCharacters` is formally invalid on every read, the computation
 * channel's included: a computation reading it is poisoned, directly, through a starred list and through an
 * index key (KERNEL-FINDINGS KF555). Validation already judged such a value invalid; the computation reads went
 * through the document's own formal check, which assumed the unrestricted default.
 *
 * Own domain (a courier manifest's parcels, keyed by tracking code).
 */
class SupportedCharactersComputeReadDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("manifest-charset").locale("en_US")
            .supportedCharacters("A-Z", "0-9")
            .rootGroup("Manifest") { m -> m.stringField("Lead") { }.stringField("Label") { }
                .numberField("LeadLength") { it.fractionDigits(0) }.stringField("FirstLabel") { }
                .numberField("Count") { it.fractionDigits(0) }.numberField("Picked") { it.fractionDigits(0) }
                .group("Parcels") { p -> p.repeatable(3).indexField("Code").stringField("Code") { }
                    .stringField("Tag") { }.numberField("Weight") { it.fractionDigits(0) } } }
            .build().toJson())
        val manifest = session.group("/Manifest")
        manifest.addComputation(manifest.num("LeadLength")) {
            it.name("LeadLengthComp").calc(Dsl.length(manifest.str("Lead"))).message(Dsl.en("Check the lead.")).build()
        }
        manifest.addComputation(manifest.str("FirstLabel")) {
            it.name("FirstLabelComp").calc(Dsl.firstFilledString(manifest.flatten("Parcels*/Tag")))
                .message(Dsl.en("Check the first label.")).build()
        }
        manifest.addComputation(manifest.num("Count")) {
            it.name("CountComp").calc(Dsl.numberOfFilledFields(manifest.flatten("Parcels*/Tag")))
                .message(Dsl.en("Check the count.")).build()
        }
        manifest.addComputation(manifest.num("Picked")) {
            it.name("PickedComp").calc(manifest.num("Parcels/Weight").forIndex(manifest.field("Label")))
                .message(Dsl.en("Check the picked weight.")).build()
        }
        manifest.addRule {
            it.name("TagCount").code("TAG_COUNT").errorField(manifest.str("Lead"))
                .`when`(manifest.str("Lead").filled().and(Dsl.numberOfFilledFields(manifest.flatten("Parcels*/Tag")).gt(0L)))
                .message(Dsl.en("The manifest carries tagged parcels.")).build()
        }
        manifest.addRule {
            it.name("PickLow").code("PICK_LOW").errorField(manifest.str("Label"))
                .`when`(manifest.num("Parcels/Weight").forIndex(manifest.field("Label")).lt(5L))
                .message(Dsl.en("The picked parcel is light.")).build()
        }
        session.toJson()
    }

    private val documents = linkedMapOf(
        "clean values" to """{"Manifest":{"Lead":"AB","Label":"P1","Parcels":[{"Code":"P1","Tag":"X","Weight":3}]}}""",
        "a lowercase lead" to """{"Manifest":{"Lead":"ab","Label":"P1","Parcels":[{"Code":"P1","Tag":"X","Weight":3}]}}""",
        "a lowercase starred tag" to """{"Manifest":{"Lead":"AB","Label":"P1","Parcels":[{"Code":"P1","Tag":"x","Weight":3}]}}""",
        "a lowercase index code" to """{"Manifest":{"Lead":"AB","Label":"p1","Parcels":[{"Code":"p1","Tag":"X","Weight":3}]}}""",
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** Each poisoned read clears exactly the computations reading it, and an authored rule reading it is silent. */
    @Test
    fun aValueOutsideTheDeclaredCharsetPoisonsEveryComputationReadingIt() {
        fun observed(document: String): Pair<Map<String, String>, List<String>> {
            val json = documents.getValue(document)
            return DocumentDifferential.kernel(model, json, dynamic = true).outcomes.toSortedMap() to
                DocumentDifferential.kernelMessages(model, json, true)
                    .map { "${it.code}@${it.pointer.removePrefix("/Manifest[1]/")}" }.sorted()
        }
        val all = mapOf("Count" to "VALUE|1", "FirstLabel" to "VALUE|X", "LeadLength" to "VALUE|2", "Picked" to "VALUE|3")
        assertEquals(all to listOf("PICK_LOW@Label", "TAG_COUNT@Lead"), observed("clean values"))
        assertEquals(all - "LeadLength" to listOf("PICK_LOW@Label", "ZeichenNichtImZeichensatz@Lead"),
            observed("a lowercase lead"))
        assertEquals(all - "Count" - "FirstLabel" to listOf("PICK_LOW@Label", "ZeichenNichtImZeichensatz@Parcels[1]/Tag"),
            observed("a lowercase starred tag"))
        assertEquals(all - "Picked" to listOf("TAG_COUNT@Lead", "ZeichenNichtImZeichensatz@Label",
            "ZeichenNichtImZeichensatz@Parcels[1]/Code"), observed("a lowercase index code"))
    }
}
