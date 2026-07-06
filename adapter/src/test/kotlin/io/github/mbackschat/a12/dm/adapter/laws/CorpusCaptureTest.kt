package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseMeta
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseSource
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.RelevantPointer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The corpus capture harness ([CorpusCapture]) — tri-verifies a scenario against the interpreter, the
 * Groovy-dynamic anchor, and the static-JAVA codegen (all through the modern `DocumentV2` + clock path), and
 * builds a [io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase] whose `expected` is the anchor.
 * Proves capture round-trips through [CaseJson] so a committed file replays what was captured.
 */
class CorpusCaptureTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    @Test
    fun capturesAValidateFullCaseWithTheAnchorExpectation() {
        val model = RuntimeLaws.withRule(order, "/Order", "QtyLow", "../Quantity", "QTY_LOW", "[Quantity] < 10")
        val placements = listOf(
            Placement(PlacementKind.GROUP, "/Order", listOf(1)),
            Placement(PlacementKind.FIELD, "/Order/Quantity", listOf(1, 1), "5"),
        )
        val meta = CaseMeta("qty-low-full", "pending", CaseSource.CURATED, listOf("§7"), tags = listOf("comparison"))
        val case = CorpusCapture.capture(meta, model, placements, Op.ValidateFull)

        assertEquals("30.8.1", case.meta.kernelVersion, "capture stamps the pinned kernel version")
        assertEquals(listOf("QTY_LOW|VALUE_ERROR|/Order[1]/Quantity"), case.expected)
        assertEquals(emptyList<Any>(), case.divergences)
        assertEquals(case, CaseJson.parse(CaseJson.write(case)), "the captured case round-trips through CaseJson")
    }

    @Test
    fun capturesAPerRowComputeCase() {
        val model = RuntimeLaws.withComputation(order, "/Order/Items", "StockComp", "../StockQty", "[Count] * 10")
        val placements = listOf(
            Placement(PlacementKind.GROUP, "/Order", listOf(1)),
            Placement(PlacementKind.GROUP, "/Order/Items", listOf(1, 1)),
            Placement(PlacementKind.FIELD, "/Order/Items/Sku", listOf(1, 1, 1), "A"),
            Placement(PlacementKind.FIELD, "/Order/Items/Count", listOf(1, 1, 1), "2"),
            Placement(PlacementKind.GROUP, "/Order/Items", listOf(1, 2)),
            Placement(PlacementKind.FIELD, "/Order/Items/Sku", listOf(1, 2, 1), "B"),
            Placement(PlacementKind.FIELD, "/Order/Items/Count", listOf(1, 2, 1), "3"),
        )
        val meta = CaseMeta("stock-per-row", "pending", CaseSource.CURATED, listOf("§2"), tags = listOf("compute"))
        val case = CorpusCapture.capture(meta, model, placements, Op.Compute)

        assertEquals(null, case.expected)
        assertEquals(
            listOf("/Order[1]/Items[1]/StockQty|VALUE|20", "/Order[1]/Items[2]/StockQty|VALUE|30"),
            case.computeExpected,
        )
        assertEquals(case, CaseJson.parse(CaseJson.write(case)))
    }

    @Test
    fun capturesAPartialCaseTaggedPartial() {
        val model = RuntimeLaws.withRule(order, "/Order", "QtyLow", "../Quantity", "QTY_LOW", "[Quantity] < 10")
        val placements = listOf(
            Placement(PlacementKind.GROUP, "/Order", listOf(1)),
            Placement(PlacementKind.FIELD, "/Order/Quantity", listOf(1, 1), "5"),
        )
        val op = Op.ValidatePart(listOf(RelevantPointer("/Order/Quantity", listOf(1, 1))))
        val meta = CaseMeta("qty-low-part", "pending", CaseSource.CURATED, listOf("§13"), tags = listOf("partial"))
        val case = CorpusCapture.capture(meta, model, placements, op)

        assertEquals(listOf("QTY_LOW|VALUE_ERROR|/Order[1]/Quantity"), case.expected)
        assertEquals(case, CaseJson.parse(CaseJson.write(case)))
    }
}
