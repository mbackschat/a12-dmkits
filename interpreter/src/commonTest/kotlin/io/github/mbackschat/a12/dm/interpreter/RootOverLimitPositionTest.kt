package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** Portable locks of the dual-kernel measurements in RootOverLimitPositionDiffTest. */
class RootOverLimitPositionTest {
    private val shipment = "/Shipment"
    private val parcels = "$shipment/Parcels"
    private val note = "$shipment/Note"
    private val quantity = "$parcels/Quantity"

    @Test
    fun rootOverflowUsesTheSerialCodeForEveryWrittenNode() {
        val model = ModelDef(
            fields = listOf(FieldDef(note, Kind.STRING), FieldDef(quantity, Kind.NUMBER)),
            rules = emptyList(), groupPaths = setOf(shipment, parcels),
            repeatableGroups = setOf(shipment, parcels), declaredReps = mapOf(shipment to 2, parcels to 2),
        )
        val doc = model.newDocument().group(shipment, 1).group(shipment, 2).group(shipment, 3)
            .group(parcels, 3, 1).field(note, "Ready", 3, 1).field(quantity, "7", 3, 1, 1)
        assertEquals(
            listOf("/Shipment[3]", "/Shipment[3]/Note[1]", "/Shipment[3]/Parcels[1]",
                "/Shipment[3]/Parcels[1]/Quantity[1]").map { "zuGrosseLfdNummer@$it" },
            DmInterpreter(model).validateFull(doc).map { "${it.code}@${it.errorKey}" }.sorted(),
        )
    }

    @Test
    fun deeperFixedGroupOverflowUsesRowAndContextCodes() {
        val model = ModelDef(
            fields = listOf(FieldDef(quantity, Kind.NUMBER)), rules = emptyList(),
            groupPaths = setOf(shipment, parcels),
        )
        val doc = model.newDocument().group(shipment, 1).group(parcels, 1, 1).group(parcels, 1, 2)
            .field(quantity, "7", 1, 2, 1)
        assertEquals(
            listOf("zuGrosseKontextnummer@/Shipment[1]/Parcels[2]/Quantity[1]",
                "zuGrosseZeile@/Shipment[1]/Parcels[2]"),
            DmInterpreter(model).validateFull(doc).map { "${it.code}@${it.errorKey}" }.sorted(),
        )
    }
}
