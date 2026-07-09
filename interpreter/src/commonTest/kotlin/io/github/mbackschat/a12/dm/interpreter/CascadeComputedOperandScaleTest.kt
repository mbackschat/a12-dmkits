package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF90 — a cascade-overlaid computed number is read back in its STORED form
 * (padded to the field's minFractionalDigits), so a downstream computation over a whole-number computed operand
 * (a constant `10` into a min-2 field, stored `"10.00"`) computes rather than being wrongly poisoned by an
 * unpadded `"10"`. The kernel differential (both strategies) is `adapter.laws.CascadeComputedOperandScaleDiffTest`.
 */
class CascadeComputedOperandScaleTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef("/Cart/Items", Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef("/Cart/Shipping", Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef("/Cart/Total", Kind.NUMBER, scale = 2, minFractionalDigits = 2),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef("/Cart/Shipping", "/Cart", "10"),
            ComputationDef("/Cart/Total", "/Cart", "[Items] + [Shipping]"),
        ),
    )

    @Test fun downstreamComputesOverAWholeNumberComputedOperand() {
        val doc = model.newDocument().group("/Cart", 1).field("/Cart/Items", "5.00", 1, 1)
        val out = DmInterpreter(model).compute(doc)
        val shipping = out.firstOrNull { it.path == "/Cart/Shipping" }
        val total = out.firstOrNull { it.path == "/Cart/Total" }
        assertEquals(ComputeOutcome.VALUE to "10.00", shipping?.outcome to shipping?.value, "Shipping stored padded")
        assertEquals(ComputeOutcome.VALUE to "15.00", total?.outcome to total?.value,
            "Total reads the padded computed Shipping (10.00), not an unpadded 10 → computes, not cleared")
    }
}
