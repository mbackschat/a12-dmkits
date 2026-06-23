package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **number comparisons round both operands to scale 19 `HALF_UP`** (the kernel's
 * `VkBigDecimal.DEFAULT_SCALE`, applied by `BedingungsOperatorHelper.vergleiche` to `<`/`>`/`==`/…).
 * Observable only beyond 19 fractional digits: a tiny literal added to `[Quantity]` (→0) makes a scale-20
 * value whose 20th digit decides the comparison after rounding (`…04` rounds down to 0, `…06` rounds up to
 * `1e-19`). Without the pre-rounding the interpreter would compare full-precision and diverge. (The
 * `MathContext(50)` arithmetic cap — the *other* precision item — is deferred to the `÷`/`^` increment.)
 */
class PrecisionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val def = ModelDef(listOf(FieldDef(Q, Kind.NUMBER)), emptyList())

    /** `{ [Quantity] + <tiny> } <op> 0` over Quantity=0 — kernel and interpreter must agree on firing. */
    private fun agree(op: String, tiny: String, msg: String) {
        val cond = "{ [Quantity] + $tiny } $op 0"
        val kModel = RuntimeLaws.withRule(order, "/Order", "Prec", "../Quantity", "PREC", cond)
        val defWithRule = ModelDef(def.fields, listOf(RuleDef("/Order/Prec", "/Order", cond, "PREC", "../Quantity")))

        val kernel = RuntimeLaws.fires(kModel, "PREC", setOf("/Order"), mapOf(Q to "0"))
        val interp = DmInterpreter(defWithRule).validateFull(Document(def.kinds).field(Q, "0", 1, 1))
            .any { it.code == "PREC" }
        assertEquals(kernel, interp, "$msg  (kernel fired=$kernel interp=$interp)")
    }

    // 4e-20 rounds DOWN to 0 at scale 19; 6e-20 rounds UP to 1e-19.
    private val roundsToZero = "0.00000000000000000004"
    private val roundsToTiny = "0.00000000000000000006"

    @Test fun equalityRoundsToScale19_downToZeroFires() = agree("==", roundsToZero, "…04 == 0 fires (rounds to 0)")
    @Test fun equalityRoundsToScale19_upStaysNonzero() = agree("==", roundsToTiny, "…06 == 0 does not fire (rounds to 1e-19)")
    @Test fun orderingRoundsToScale19_downIsNotGreater() = agree(">", roundsToZero, "…04 > 0 does not fire (rounds to 0)")
    @Test fun orderingRoundsToScale19_upIsGreater() = agree(">", roundsToTiny, "…06 > 0 fires (rounds to 1e-19)")

    private companion object {
        const val Q = "/Order/Quantity"
    }
}
