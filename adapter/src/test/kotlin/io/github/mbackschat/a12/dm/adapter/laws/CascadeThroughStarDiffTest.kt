package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.NumberFieldBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the **computation cascade THROUGH a star aggregate over a computed field**: a model-level
 * `Total := Sum(Lines* then Doubled)` where `Doubled := [Qty] * 2` is itself a per-Line computed field. The
 * kernel computes `Doubled` per Line (its `CalculationCache`) and then sums the COMPUTED values; the interpreter
 * must do the same — its compute overlay has to reach a STARRED read, not just direct/scalar refs
 * (KERNEL-FINDINGS §11). Without that, the sum folds the stored (empty → 0) `Doubled` cells and `Total` is wrong.
 */
class CascadeThroughStarDiffTest {

    private val structureJson = DocumentModelDraft.named("calc").locale("en_US")
        .rootGroup("Calc", Consumer { root ->
            root.numberField("Total", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
            root.group("Lines", Consumer { g ->
                g.repeatable(100)
                g.numberField("Qty", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                g.numberField("Doubled", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
            })
        })
        .build().toJson()
    // Operations authored with the rulekit typed DSL ([m]), each rendered relative to its computation
    // group (Doubled in /Calc/Lines, Total in /Calc) to the canonical string fed to BOTH engines.
    private val m = BoundModel.ofJson(structureJson)
    private fun renderOp(c: Calculation, group: String): String = RuntimeLaws.render(group, c)
    private val opDoubled = renderOp(m.num("/Calc/Lines/Qty").asNumber().times(NumberExpr(NumberLiteral.of(2))).expression(), "/Calc/Lines")
    private val opTotal = renderOp(Dsl.sum(m.flatten("/Calc/Lines*/Doubled")).expression(), "/Calc")
    private val kModel = RuntimeLaws.withComputation(
        RuntimeLaws.withComputation(structureJson, "/Calc/Lines", "DC", "../Doubled", opDoubled),
        "/Calc", "TC", "../Total", opTotal)
    private val def = ModelDef(
        listOf(
            FieldDef("/Calc/Total", Kind.NUMBER, scale = 0),
            FieldDef("/Calc/Lines/Qty", Kind.NUMBER, scale = 0),
            FieldDef("/Calc/Lines/Doubled", Kind.NUMBER, scale = 0),
        ),
        emptyList(),
        computations = listOf(
            ComputationDef("/Calc/Lines/Doubled", "/Calc/Lines", opDoubled),
            ComputationDef("/Calc/Total", "/Calc", opTotal),
        ),
        repeatableGroups = setOf("/Calc/Lines"),
        declaredReps = mapOf("/Calc/Lines" to 100),
    )

    @Test
    fun totalSumsTheCOMPUTEDLineValuesLikeKernel() {
        val kDoc = RuntimeLaws.doc().group("/Calc", 1)
            .group("/Calc/Lines", 1, 1).field("/Calc/Lines/Qty", "5", 1, 1, 1)
            .group("/Calc/Lines", 1, 2).field("/Calc/Lines/Qty", "3", 1, 2, 1)
        val iDoc = def.newDocument()
            .group("/Calc", 1)
            .group("/Calc/Lines", 1, 1).field("/Calc/Lines/Qty", "5", 1, 1, 1)
            .group("/Calc/Lines", 1, 2).field("/Calc/Lines/Qty", "3", 1, 2, 1)

        val k = RuntimeLaws.compute(kModel, kDoc)
        val i = DmInterpreter(def).compute(iDoc)
        fun interp(path: String) = i.firstOrNull { it.path == path }?.value

        // Doubled = 10, 6 ; Total = Sum of the COMPUTED Doubled = 16 (NOT 0 from the stored-empty cells).
        assertEquals("16", RuntimeLaws.computedValue(k, "/Calc/Total").orElse(null), "kernel sums computed Doubled → 16")
        assertEquals(RuntimeLaws.computedValue(k, "/Calc/Total").orElse(null), interp("/Calc/Total"),
            "interp Total == kernel (sums the computed Doubled, not the stored-empty cells)")
    }
}
