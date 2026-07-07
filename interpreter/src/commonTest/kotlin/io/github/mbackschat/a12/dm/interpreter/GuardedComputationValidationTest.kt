package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF65: a computation's implicit validation (`FieldFilled(F) And [F] != <op>`,
 * fired under the computation's NAME) is generated for a GUARDED / TIERED computation too, not only the degenerate
 * single-unconditional one. Runs the same kernel-free evaluator on both targets; the kernel differential (firing +
 * type + per-row pointer + codegen tri-check) is `adapter.laws.ComputationGuardedValidationDiffTest` (JVM only).
 */
class GuardedComputationValidationTest {

    private val qty = "/Order/Quantity"
    private val back = "/Order/BackorderQuantity"

    // BackorderQuantity = [Quantity], guarded by a commonPrecondition FieldFilled(Quantity), name "BackComp".
    private fun guarded() = ModelDef(
        fields = listOf(FieldDef(qty, Kind.NUMBER, scale = 0), FieldDef(back, Kind.NUMBER, scale = 0)),
        rules = emptyList(),
        computations = listOf(ComputationDef(back, "/Order",
            listOf(ComputationAlternative(null, "[Quantity]")), commonPrecondition = "FieldFilled(Quantity)", name = "BackComp")),
    )

    // Tiered: Quantity < 100 → 10 ; Quantity >= 100 → 20 (the Or-of-gated-comparisons implicit validation).
    private fun tiered() = ModelDef(
        fields = listOf(FieldDef(qty, Kind.NUMBER, scale = 0), FieldDef(back, Kind.NUMBER, scale = 0)),
        rules = emptyList(),
        computations = listOf(ComputationDef(back, "/Order",
            listOf(ComputationAlternative("[Quantity] < 100", "10"), ComputationAlternative("[Quantity] >= 100", "20")),
            name = "BackComp")),
    )

    private fun doc(m: ModelDef, q: String?, stored: String?): Document = m.newDocument().group("/Order", 1).also { d ->
        if (q != null) d.field(qty, q, 1, 1)
        if (stored != null) d.field(back, stored, 1, 1)
    }

    private fun fires(m: ModelDef, doc: Document) = DmInterpreter(m).validateFull(doc).any { it.code == "BackComp" }

    @Test fun guardTrueDisagreeFires() =
        assertEquals(true, fires(guarded(), doc(guarded(), "5", "99")), "guard TRUE, stored 99 ≠ computed 5 → implicit validation fires")

    @Test fun guardTrueAgreeDoesNotFire() =
        assertEquals(false, fires(guarded(), doc(guarded(), "5", "5")), "guard TRUE, stored 5 == computed 5 → no fire")

    @Test fun guardFalseDoesNotFire() =
        assertEquals(false, fires(guarded(), doc(guarded(), null, "99")), "guard FALSE (Quantity empty) → cleared, no fire")

    @Test fun tieredSelectedTierDisagreeFires() =
        assertEquals(true, fires(tiered(), doc(tiered(), "5", "99")), "tiered: low tier (Quantity<100 → 10), stored 99 ≠ 10 → fires")

    @Test fun tieredSelectedTierAgreeDoesNotFire() =
        assertEquals(false, fires(tiered(), doc(tiered(), "5", "10")), "tiered: low tier, stored 10 == 10 → no fire")
}
