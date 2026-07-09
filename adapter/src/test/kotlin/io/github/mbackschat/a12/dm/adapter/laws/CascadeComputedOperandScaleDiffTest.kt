package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for the IG44 VCV over-suppression
 * atom (`kernel=VALUE,interp=CLEARED`), root-caused by shrinking a real cascade to its minimal interaction:
 * a computed field whose value is a **whole number** (a constant `10`) into a `minFractionalDigits=2` field,
 * READ by a downstream computation.
 *
 * A computed NUMBER is STORED padded to its target's `minFractionalDigits` (IF82) — the kernel stores `10` as
 * `"10.00"`. The interpreter's cascade overlay carried the numeric `Val.Num(10)` correctly for arithmetic, but
 * its `displayValue` stringified it with the RAW scale (`"10"`, 0 fractional digits). So when the downstream
 * computation's poison gate (`computeFormalErrorAt`) formal-checked the operand, it saw `"10"` in a min-2 field
 * → `zahlFalscheAnzahlNachkommastellen` (too few fractional digits) → poisoned → CLEARED the downstream field,
 * where the kernel reads the stored `"10.00"` (valid) and computes. IF90 pads the overlay's `displayValue` to
 * the target's minFractionalDigits, matching the stored form.
 *
 * Own-domain re-expression (a shopping cart total); nothing from the corpus is reproduced.
 */
class CascadeComputedOperandScaleDiffTest {

    private companion object {
        const val ITEMS = "/Cart/Items"
        const val SHIPPING = "/Cart/Shipping"
        const val TOTAL = "/Cart/Total"
    }

    private val json = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { g: GroupBuilder ->
            g.numberField("Items", Consumer { it.fractionDigits(2, 2) })
                .numberField("Shipping", Consumer { it.fractionDigits(2, 2) })
                .numberField("Total", Consumer { it.fractionDigits(2, 2) })
        })
        .build().toJson()

    // Shipping = 10 (a whole-number constant → stored "10.00"); Total = Items + Shipping (reads computed Shipping).
    private val kModel = RuntimeLaws.withComputation(
        RuntimeLaws.withComputation(json, "/Cart", "ShipComp", "../Shipping", "10"),
        "/Cart", "TotalComp", "../Total", "[Items] + [Shipping]")
    private val def = ModelDef(
        listOf(
            FieldDef(ITEMS, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(SHIPPING, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(TOTAL, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
        ),
        emptyList(),
        computations = listOf(
            ComputationDef(SHIPPING, "/Cart", "10"),
            ComputationDef(TOTAL, "/Cart", "[Items] + [Shipping]"),
        ),
    )

    private fun jTotal(): String? {
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val doc = RuntimeLaws.doc().group("/Cart", 1).field(ITEMS, "5.00", 1, 1)
        val raw = engine.computeOutcomesSorted(engine.instance(doc)).firstOrNull { it.startsWith("$TOTAL|") }
            ?.substringAfter("$TOTAL|") ?: return "NONE"
        return when (raw) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> raw }
    }

    @Test fun downstreamComputesOverAWholeNumberComputedOperand() {
        val kdoc = RuntimeLaws.doc().group("/Cart", 1).field(ITEMS, "5.00", 1, 1)
        val kTotal = RuntimeLaws.computedValue(RuntimeLaws.compute(kModel, kdoc), TOTAL).orElse(null)
        val idoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .group("/Cart", 1).field(ITEMS, "5.00", 1, 1)
        val iTotal = DmInterpreter(def).compute(idoc).firstOrNull { it.path == TOTAL }?.value

        assertEquals("15.00", kTotal, "kernel: Items(5.00) + Shipping(computed 10 → stored 10.00) = 15.00")
        assertEquals("15.00", jTotal(), "java-static codegen agrees (tri-check)")
        assertEquals(kTotal, iTotal, "interpreter matches — was CLEARED before IF90 (overlay displayed 10 as \"10\")")
    }
}
