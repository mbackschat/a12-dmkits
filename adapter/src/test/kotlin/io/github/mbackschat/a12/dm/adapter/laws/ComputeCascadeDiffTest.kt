package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the **computation cascade**: a computed field referencing ANOTHER computed field. The kernel
 * computes the dependency first (its `CalculationCache`) and the dependent reads the COMPUTED value; the
 * interpreter must do the same (compute in dependency order + write each result back so dependents see it).
 * Before the fix the interpreter read the dependency's STORED (empty → 0) operand → a silent wrong value
 * (`BackorderQuantity = 0 + 1 = 1` vs the kernel's `10 + 1 = 11`). Chain: `StockOnHand := Quantity*2`, then
 * `BackorderQuantity := StockOnHand + 1` (all scale-0 counts on the non-repeatable `/Order`).
 */
class ComputeCascadeDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    // Operations authored with the rulekit typed DSL ([m]), rendered (relative to the computation group
    // /Order) to the canonical string fed to BOTH engines — no free-text operation literals.
    private val m = BoundModel.ofJson(order)
    private fun renderOp(c: Calculation): String = DslRenderer(ConditionLanguage.EN, "/Order").render(c)
    private val opA = renderOp(m.num("/Order/Quantity").asNumber().times(NumberExpr(NumberLiteral.of(2))).expression())
    private val opB = renderOp(m.num("/Order/StockOnHand").asNumber().plus(1).expression())
    private val kModel = RuntimeLaws.withComputation(
        RuntimeLaws.withComputation(order, "/Order", "CompA", "../StockOnHand", opA),
        "/Order", "CompB", "../BackorderQuantity", opB)
    private val def = ModelDef(
        listOf(FieldDef("/Order/Quantity", Kind.NUMBER), FieldDef("/Order/StockOnHand", Kind.NUMBER), FieldDef("/Order/BackorderQuantity", Kind.NUMBER)),
        emptyList(),
        computations = listOf(
            ComputationDef("/Order/StockOnHand", "/Order", opA),
            ComputationDef("/Order/BackorderQuantity", "/Order", opB),
        ),
    )

    @Test
    fun chainedComputationCascadesLikeKernel() {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field("/Order/Quantity", "5", 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats, def.datePrecisions).group("/Order", 1).field("/Order/Quantity", "5", 1, 1)

        val k = RuntimeLaws.compute(kModel, kDoc)
        val i = DmInterpreter(def).compute(iDoc)
        fun interp(path: String) = i.firstOrNull { it.path == path }?.value

        // dependency computes Quantity*2 = 10; dependent reads the COMPUTED 10, +1 = 11 (NOT the stored-empty 0+1=1)
        assertEquals("10", RuntimeLaws.computedValue(k, "/Order/StockOnHand").orElse(null), "kernel StockOnHand")
        assertEquals("11", RuntimeLaws.computedValue(k, "/Order/BackorderQuantity").orElse(null), "kernel cascades to 11")
        assertEquals(RuntimeLaws.computedValue(k, "/Order/StockOnHand").orElse(null), interp("/Order/StockOnHand"), "interp StockOnHand == kernel")
        assertEquals(RuntimeLaws.computedValue(k, "/Order/BackorderQuantity").orElse(null), interp("/Order/BackorderQuantity"), "interp cascades == kernel (11, not 1)")
    }
}
