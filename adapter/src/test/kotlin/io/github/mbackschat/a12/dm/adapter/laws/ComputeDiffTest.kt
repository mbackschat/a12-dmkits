package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for computations ([DmInterpreter.compute]) — the `model compute` shape. The computed
 * value must match the kernel's `compute()` over the same instance, including the empty-as-0 trap that
 * the cli-runtime demo highlights: `EffectiveFee = [BaseFee]` is `49.9` when filled, `0` when BaseFee is
 * absent (an empty numeric operand reads as 0). Mirrors cli-runtime.md's `subscription-computed` model.
 */
class ComputeDiffTest {

    private val subscription = RuntimeLaws.model("models/subscription.dm.json")
    // Operations are authored with the rulekit typed DSL ([m]) and rendered (relative to the computation
    // group) to the canonical string fed to BOTH engines — no free-text operation literals.
    private val m = BoundModel.ofJson(subscription)
    private fun renderOp(c: Calculation): String = RuntimeLaws.render("/Subscription/Billing", c)
    private val fields = listOf(FieldDef(BASE, Kind.NUMBER), FieldDef(EFF, Kind.NUMBER))
    private val operation = renderOp(m.num(BASE).asNumber().expression())
    private val comp = ComputationDef(EFF, "/Subscription/Billing", operation)
    private val def = ModelDef(fields, emptyList(), computations = listOf(comp))
    private val model = RuntimeLaws.withComputation(subscription, "/Subscription/Billing", "EffectiveFeeComp", "../EffectiveFee", operation)

    @Test
    fun filledBaseFeeComputesTheSameValueAsKernel() {
        val kernel = RuntimeLaws.compute(model, RuntimeLaws.doc()
            .group("/Subscription", 1).group("/Subscription/Billing", 1, 1)
            .field(BASE, "49.90", 1, 1, 1))
        val kernelValue = RuntimeLaws.computedValue(kernel, EFF).orElse(null)

        val interp = DmInterpreter(def).compute(Document(def.kinds).field(BASE, "49.90", 1, 1, 1))
        val got = interp.single { it.path == EFF }
        assertEquals("49.9", kernelValue, "kernel renders 49.90 → 49.9")
        assertEquals(ComputeOutcome.VALUE, got.outcome)
        assertEquals(kernelValue, got.value, "interpreter computes the same value as the kernel")
    }

    @Test
    fun emptyBaseFeeComputesZeroLikeKernel() {
        // BaseFee absent — the Billing group is present but empty (cli-runtime's empty-operand case)
        val kernel = RuntimeLaws.compute(model, RuntimeLaws.doc()
            .group("/Subscription", 1).group("/Subscription/Billing", 1, 1))
        val kernelValue = RuntimeLaws.computedValue(kernel, EFF).orElse(null)

        val interp = DmInterpreter(def).compute(Document(def.kinds).group("/Subscription/Billing", 1, 1))
        val got = interp.single { it.path == EFF }
        assertEquals("0", kernelValue, "empty numeric operand → 0 (empty-as-0)")
        assertEquals(ComputeOutcome.VALUE, got.outcome)
        assertEquals(kernelValue, got.value, "interpreter computes 0 like the kernel")
    }

    private companion object {
        const val BASE = "/Subscription/Billing/BaseFee"
        const val EFF = "/Subscription/Billing/EffectiveFee"
    }
}
