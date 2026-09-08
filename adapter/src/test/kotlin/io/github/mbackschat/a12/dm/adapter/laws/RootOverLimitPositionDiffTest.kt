package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import java.util.Locale
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RootOverLimitPositionDiffTest {
    @Test
    fun repeatableRootAndDescendantsReportSerialWhileDeeperExcessReportsRowAndContext() {
        val json = model(2)
        compare(json, 2, 2, emptyList())
        compare(json, 3, 1, rootErrors(3, 1))
        compare(json, 1, 3, deeperErrors(3))
        compare(json, 3, 3, rootErrors(3, 3))
    }

    @Test
    fun fixedGroupCapsUseTheSameCoordinatePositions() {
        val json = model(1)
        compare(json, 1, 1, emptyList())
        compare(json, 2, 1, rootErrors(2, 1))
        compare(json, 1, 2, deeperErrors(2))
    }

    private fun model(cap: Int): String = DocumentModelDraft.named("shipment-addresses").locale("en_US")
        .rootGroup("Shipment", Consumer { shipment ->
            if (cap > 1) shipment.repeatable(cap)
            shipment.stringField("Note", Consumer { })
                .group("Parcels", Consumer { parcels ->
                    if (cap > 1) parcels.repeatable(cap)
                    parcels.numberField("Quantity", Consumer { })
                        .group("Labels", Consumer { labels ->
                            labels.repeatable(2).stringField("Text", Consumer { })
                        })
                })
        }).build().toJson()

    private fun compare(json: String, root: Int, parcel: Int, expected: List<String>) {
        val def = ModelLoader.load(json)
        val shipment = EditSession.read(json).group("/Shipment")
        val shipmentPath = shipment.ref().path().value()
        val parcelsPath = shipment.group("Parcels").path().value()
        val note = shipment.field("Note").path().value()
        val quantity = shipment.field("Parcels/Quantity").path().value()
        val label = shipment.field("Parcels/Labels/Text").path().value()
        val kernel = RuntimeLaws.doc()
            .field(note, "Ready", root, 1)
            .field(quantity, "7", root, parcel, 1)
            .field(label, "Fragile", root, parcel, 1, 1)
        val document = def.newDocument()
            .field(note, "Ready", root, 1)
            .field(quantity, "7", root, parcel, 1)
            .field(label, "Fragile", root, parcel, 1, 1)
        // RuntimeLaws materializes dense groups, so mirror those physical rows before comparing exact keys.
        for (r in 1..root) document.group(shipmentPath, r)
        for (p in 1..parcel) document.group(parcelsPath, root, p)
        val dynamic = RuntimeLaws.messages(json, kernel).map { "${it.code()}@${it.key()}" }.sorted()
        val static = RuntimeLaws.kernelJavaEngine(json).messagesModern(kernel, null, Locale.US)
            .map { "${it.code()}@${it.key()}" }.sorted()
        assertEquals(expected.sorted(), dynamic, "kernel root=$root parcel=$parcel")
        assertEquals(dynamic, static, "both kernel strategies")
        assertEquals(dynamic, DmInterpreter(def).validateFull(document).map { "${it.code}@${it.errorKey}" }.sorted())
    }

    private fun rootErrors(root: Int, parcel: Int): List<String> {
        val prefix = "/Shipment[$root]"
        return (listOf(prefix, "$prefix/Note[1]", "$prefix/Parcels[$parcel]/Quantity[1]",
            "$prefix/Parcels[$parcel]/Labels[1]", "$prefix/Parcels[$parcel]/Labels[1]/Text[1]") +
            (1..parcel).map { "$prefix/Parcels[$it]" }).map { "zuGrosseLfdNummer@$it" }
    }

    private fun deeperErrors(parcel: Int): List<String> {
        val prefix = "/Shipment[1]/Parcels[$parcel]"
        return listOf("zuGrosseZeile@$prefix", "zuGrosseKontextnummer@$prefix/Quantity[1]",
            "zuGrosseKontextnummer@$prefix/Labels[1]", "zuGrosseKontextnummer@$prefix/Labels[1]/Text[1]")
    }
}
