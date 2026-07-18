package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for TIERED computations (IF21) — a computation's `computationAlternatives` are selected by
 * their `precondition` (first match wins; none → cleared), optionally gated by a `commonPrecondition`. The
 * interpreter's [DmInterpreter.compute] must agree with the kernel's `compute()` over the same instance.
 *
 * Models `UnitPrice` (scale-2) tiered on `Quantity` (scale-0) in the order fixture. Before IF21 the
 * interpreter took only `alternatives[0].operation` and computed `9.99` always — these lock the fix.
 */
class ComputationPreconditionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER, scale = 2))

    // Preconditions and operations are authored with the rulekit typed DSL ([m]) and rendered (relative to
    // the computation group /Order) to the canonical strings fed to BOTH engines — no free-text literals.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer(ConditionLanguage.EN, "/Order").render(c)
    private fun renderOp(c: Calculation): String = DslRenderer(ConditionLanguage.EN, "/Order").render(c)
    private fun op(value: String): String = renderOp(NumberLiteral.of(value))

    /** Compute UnitPrice for the given [quantity] under [common] + [alts] on BOTH engines; assert they agree. */
    private fun agree(common: String?, alts: List<Pair<String?, String>>, quantity: String?, msg: String) {
        val kAlts = alts.map { RuntimeLaws.CompAlt(it.first, it.second) }
        val kModel = RuntimeLaws.withTieredComputation(order, "/Order", "Tier", "../UnitPrice", common, kAlts)
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        if (quantity != null) kDoc.field(QTY, quantity, 1, 1)
        val kVal = RuntimeLaws.computedValue(RuntimeLaws.compute(kModel, kDoc), PRICE).orElse(null)
        val cleared = RuntimeLaws.cleared(RuntimeLaws.compute(kModel, kDoc), PRICE)

        val def = ModelDef(
            fields, emptyList(),
            computations = listOf(ComputationDef(PRICE, "/Order", alts.map { ComputationAlternative(it.first, it.second) }, common)),
        )
        val iDoc = def.newDocument().group("/Order", 1)
        if (quantity != null) iDoc.field(QTY, quantity, 1, 1)
        val out = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == PRICE }
        val iVal = out?.value

        // Kernel "cleared" (no value) ≡ interpreter CLEARED/absent value.
        if (kVal == null && cleared) assertEquals(null, iVal, "$msg — both cleared (kernel value=$kVal cleared=$cleared, interp=$out)")
        else assertEquals(kVal, iVal, "$msg (kernel=$kVal interp=$iVal)")
    }

    private val tier = listOf<Pair<String?, String>>(render(m.num(QTY).lt(100)) to op("9.99"), render(m.num(QTY).ge(100)) to op("0"))

    @Test fun lowPicksFirstAlternative() = agree(null, tier, "50", "Quantity<100 → first alt 9.99")
    @Test fun highPicksSecondAlternative() = agree(null, tier, "150", "Quantity>=100 → second alt 0")

    // A gap between the alternatives' preconditions: no alternative matches → the field is cleared.
    private val gapped = listOf<Pair<String?, String>>(render(m.num(QTY).lt(50)) to op("1"), render(m.num(QTY).gt(100)) to op("2"))

    @Test fun noAlternativeMatchesClears() = agree(null, gapped, "75", "75 in [50,100] → no alt → cleared")
    @Test fun gappedLowMatches() = agree(null, gapped, "20", "20<50 → first alt 1")

    // NOTE: a trailing UNCONDITIONAL alternative mixed with guarded ones is a KERNEL-INVALID model
    // (MVK_ERROR_EMPTY_PRECONDITION — "Empty prerequisite is not allowed here"); an unconditional operation
    // is valid only as the SOLE alternative (the ordinary non-tiered computation). So it is not differential-
    // tested — the kernel cannot author it. The interpreter still treats a null precondition as always-true.

    // commonPrecondition gates the whole computation: false → cleared regardless of the alternatives.
    @Test fun commonPreconditionFalseClears() =
        agree(render(m.num(QTY).ge(100)), tier, "50", "common [Quantity]>=100 is false at 50 → cleared")
    @Test fun commonPreconditionTrueProceeds() =
        agree(render(m.num(QTY).ge(100)), listOf(null to op("7")), "150", "common true at 150 → unconditional 7")

    private companion object {
        const val QTY = "/Order/Quantity"
        const val PRICE = "/Order/UnitPrice"
    }
}
