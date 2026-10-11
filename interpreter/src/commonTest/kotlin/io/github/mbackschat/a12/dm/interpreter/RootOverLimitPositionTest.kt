package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** Portable locks of RootOverLimitPositionDiffTest and OverLimitOperandComputeArmDiffTest. */
class RootOverLimitPositionTest {
    private val shipment = "/Shipment"
    private val parcels = "$shipment/Parcels"
    private val note = "$shipment/Note"
    private val quantity = "$parcels/Quantity"

    @Test
    fun computationDiagnosticsKeepDistinctTerminalGroupCoordinates() {
        val root = "/Claim"
        val payouts = "$root/Payouts"
        val items = "$payouts/Items"
        val splits = "$items/Splits"
        val part = "$splits/Part"
        val total = "$root/Total"
        val model = ModelDef(
            fields = listOf(FieldDef(part, Kind.NUMBER), FieldDef(total, Kind.NUMBER)),
            rules = emptyList(), groupPaths = setOf(root, payouts, items, splits),
            repeatableGroups = setOf(items, splits), declaredReps = mapOf(items to 2, splits to 2),
            computations = listOf(ComputationDef(total, root, "Sum(Payouts/Items*/Splits*/Part)")),
        )
        val doc = model.newDocument().group(root, 1).group(payouts, 1, 1)
            .group(items, 1, 1, 1).group(items, 1, 1, 2).group(items, 1, 1, 3)
            .group(splits, 1, 1, 3, 1).group(splits, 1, 1, 3, 2)
            .field(part, "9", 1, 1, 3, 1, 1).field(part, "8", 1, 1, 3, 2, 1)
        val report = DmInterpreter(model).retainedComputations(doc)
        assertEquals(
            listOf(
                "zuGrosseKontextnummer@/Claim[1]/Payouts[1]/Items[3]/Splits[1]",
                "zuGrosseKontextnummer@/Claim[1]/Payouts[1]/Items[3]/Splits[1]/Part[1]",
                "zuGrosseKontextnummer@/Claim[1]/Payouts[1]/Items[3]/Splits[2]",
                "zuGrosseKontextnummer@/Claim[1]/Payouts[1]/Items[3]/Splits[2]/Part[1]",
                "zuGrosseZeile@/Claim[1]/Payouts[1]/Items[3]",
            ),
            report.formalErrorsInOperands.map { "${it.code}@${it.errorKey}" }.sorted(),
        )
    }

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
