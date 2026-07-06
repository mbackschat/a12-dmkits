package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **Per-row compute identity** (CONFORMANCE-CORPUS-SPEC §7, WI-3b obligation). A computation over a REPEATABLE
 * group produces one outcome per row; the corpus records each by its **per-row pointer**
 * (`formatPointer(path, indices)`), superseding the historical index-strip that could not tell the right value
 * on the wrong row apart. This locks that:
 *
 * 1. the interpreter's [DmInterpreter.compute] carries the target cell's [io.github.mbackschat.a12.dm.interpreter.model.Computed.indices]
 *    (so `/Order[1]/Items[2]/StockQty` ≠ `/Order[1]/Items[1]/StockQty`), and
 * 2. that per-row form **matches the kernel's** `IComputedFieldInstance.pointer()` rendered the same way —
 *    row for row, as a multiset.
 */
class ComputePerRowIdentityDiffTest {

    private val base = RuntimeLaws.model("models/order.dm.json")
    // StockQty = Count * 10, per Items row — a computed field on a repeatable group, so two rows compute two
    // distinct per-row outcomes. Injected into BOTH engines from the same DM-JSON (kernel via withComputation,
    // interpreter via the kernel-free loader).
    private val model = RuntimeLaws.withComputation(base, "/Order/Items", "StockComp", "../StockQty", "[Count] * 10")
    private val def = ModelLoader.load(model)

    private fun kernelDoc() = RuntimeLaws.doc()
        .group("/Order", 1)
        .group("/Order/Items", 1, 1).field("/Order/Items/Sku", "A", 1, 1, 1).field("/Order/Items/Count", "2", 1, 1, 1)
        .group("/Order/Items", 1, 2).field("/Order/Items/Sku", "B", 1, 2, 1).field("/Order/Items/Count", "3", 1, 2, 1)

    private fun interpDoc() = def.newDocument()
        .group("/Order", 1)
        .group("/Order/Items", 1, 1).field("/Order/Items/Sku", "A", 1, 1, 1).field("/Order/Items/Count", "2", 1, 1, 1)
        .group("/Order/Items", 1, 2).field("/Order/Items/Sku", "B", 1, 2, 1).field("/Order/Items/Count", "3", 1, 2, 1)

    @Test
    fun computeCarriesPerRowIndicesMatchingTheKernelPointer() {
        val interp = DmInterpreter(def).compute(interpDoc()).filter { it.path == STOCK }
        // The interpreter distinguishes the two rows by their repetition indices (not a collapsed bare path).
        assertEquals(
            listOf(listOf(1, 1, 1), listOf(1, 2, 1)),
            interp.map { it.indices }.sortedBy { it.toString() },
            "each computed row carries its own repetition indices",
        )
        assertEquals(setOf(ComputeOutcome.VALUE), interp.map { it.outcome }.toSet())

        val interpSigs = interp.map { "${formatPointer(it.path, it.indices)}|${it.value}" }.sorted()
        val kernelSigs = RuntimeLaws.compute(model, kernelDoc()).filter { it.pointer().endsWith("StockQty") }
            .map { "${it.pointer()}|${it.value().orElse("")}" }.sorted()

        assertEquals(
            listOf("/Order[1]/Items[1]/StockQty|20", "/Order[1]/Items[2]/StockQty|30"),
            kernelSigs,
            "kernel renders per-row computed pointers the shared formatPointer way",
        )
        assertEquals(kernelSigs, interpSigs, "interpreter per-row compute pointers match the kernel row for row")
    }

    private companion object {
        const val STOCK = "/Order/Items/StockQty"
    }
}
