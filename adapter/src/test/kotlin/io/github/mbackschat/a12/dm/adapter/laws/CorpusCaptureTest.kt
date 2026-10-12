package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseMeta
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseSource
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.RelevantPointer
import io.github.mbackschat.a12.dm.interpreter.conformance.InterpreterReplay
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
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
        val case = CorpusCapture.capture(meta, "order-qty-low", model, placements, Op.ValidateFull)

        assertEquals("30.8.1", case.meta.kernelVersion, "capture stamps the pinned kernel version")
        assertEquals("models/order-qty-low.json", case.modelRef, "capture references the standalone model file")
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
        val case = CorpusCapture.capture(meta, "order-stock-compute", model, placements, Op.Compute)

        assertEquals(null, case.expected)
        assertEquals(
            listOf("/Order[1]/Items[1]/StockQty|VALUE|20", "/Order[1]/Items[2]/StockQty|VALUE|30"),
            case.computeExpected,
        )
        assertEquals(case, CaseJson.parse(CaseJson.write(case)))
    }

    @Test
    fun capturesASeedEqualStringCellAtKernelGranularity() {
        val model = RuntimeLaws.withComputation(order, "/Order", "NameComp", "../CustomerName", "[ProductCode] + \"-X\"")
        val placements = listOf(
            Placement(PlacementKind.GROUP, "/Order", listOf(1)),
            Placement(PlacementKind.FIELD, "/Order/ProductCode", listOf(1, 1), "AB"),
            Placement(PlacementKind.FIELD, "/Order/CustomerName", listOf(1, 1), "AB-X"),
        )
        val meta = CaseMeta("name-seed-equal", "pending", CaseSource.CURATED, listOf("§2"), tags = listOf("compute"))
        val case = CorpusCapture.capture(meta, "order-name-compute", model, placements, Op.Compute)

        assertEquals(
            emptyList<String>(),
            case.computeExpected,
            "the computed AB-X equals its seed — a kernel non-change; the interpreter's report-all VALUE is projected away",
        )
    }

    @Test
    fun captureUsesCanonicalNumberIdentityAndExplicitV1KeepsItsStringProfile() {
        val bound = BoundModel.ofJson(order)
        val items = bound.groupAt("/Order/Items").orElseThrow()
        val count = items.num("Count")
        val stock = items.num("StockQty")
        val sku = items.str("Sku")
        val model = bound.add(bound.computation(stock).name("StockComp")
            .calc(count.times(10).expression()).message(Dsl.en("Stock is derived.")).build()).toJson()
        val legacy = listOf("/Order[1]/Items[1]/StockQty|VALUE|20", "/Order[1]/Items[2]/StockQty|VALUE|30")
        for (seed in listOf("20", "20.0")) {
            val placements = listOf(
                Placement(PlacementKind.GROUP, "/Order", listOf(1)),
                Placement(PlacementKind.GROUP, items.path(), listOf(1, 1)),
                Placement(PlacementKind.FIELD, sku.field().path().value(), listOf(1, 1, 1), "A"),
                Placement(PlacementKind.FIELD, count.field().path().value(), listOf(1, 1, 1), "2"),
                Placement(PlacementKind.FIELD, stock.field().path().value(), listOf(1, 1, 1), seed),
                Placement(PlacementKind.GROUP, items.path(), listOf(1, 2)),
                Placement(PlacementKind.FIELD, sku.field().path().value(), listOf(1, 2, 1), "B"),
                Placement(PlacementKind.FIELD, count.field().path().value(), listOf(1, 2, 1), "3"),
            )
            assertEquals(legacy, CorpusEngines.groovyDynamic(model, placements, Op.Compute), "explicit V1 dynamic seed $seed")
            assertEquals(legacy, CorpusEngines.javaStatic(model, placements, Op.Compute), "explicit V1 static seed $seed")
            val meta = CaseMeta("stock-seed-equal", "pending", CaseSource.CURATED, listOf("§2"), tags = listOf("compute"))
            val case = CorpusCapture.capture(meta, "order-stock-compute", model, placements, Op.Compute)
            val expected = if (seed == "20") legacy.drop(1) else legacy
            assertEquals(expected, case.computeExpected, "canonical Number source scale is part of identity")
            assertEquals(case, CaseJson.parse(CaseJson.write(case)))
            for ((engine, result) in CorpusReplay.replay(case, model)) {
                assertEquals(InterpreterReplay.Verdict.PASS, result.verdict, "$engine / seed $seed: ${result.detail}")
            }
        }
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
        val case = CorpusCapture.capture(meta, "order-qty-low", model, placements, op)

        assertEquals(listOf("QTY_LOW|VALUE_ERROR|/Order[1]/Quantity"), case.expected)
        assertEquals(case, CaseJson.parse(CaseJson.write(case)))
    }
}
