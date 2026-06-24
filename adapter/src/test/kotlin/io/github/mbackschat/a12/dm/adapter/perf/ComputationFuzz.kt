package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValueStrategy
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.NumberFieldBuilder
import java.util.function.Consumer
import kotlin.random.Random

/**
 * **Harness F — computation-cascade fuzzing.** The model/document fuzz nets ([ModelFuzz], [PerfCatalog.fuzz])
 * only ever fuzz **validation**; the **compute** path was exercised by exactly ONE hand-picked case
 * (`ComputeCascadeDiffTest`) — which is *why* a silent cascade bug (a computed field reading a dependency's
 * STORED-empty operand instead of its COMPUTED value) survived undetected until found by hand. This net closes
 * that blind spot: it generates random **chains of scalar computations** where each `C{i}` references the
 * previous `C{i-1}` (plus base fields + constants), so the cascade is always present and at random depth, then
 * asserts the interpreter's `compute()` agrees with the real kernel's on every computed-field outcome.
 *
 * The model is a SINGLE non-repeatable root group `/Calc` — deliberately: a non-repeatable scope yields exactly
 * one row, so computed-field paths carry **no repetition indices** and the kernel's `compute()` outcomes line
 * up one-to-one with the interpreter's `targetPath`s (no normalization, no row-identity ambiguity). The cascade
 * is the property under test, not row iteration (that is covered by the validation nets). Built
 * **valid-by-construction** via modelkit's `toJson()` (kernel) + a matching [ModelDef] (interpreter); each
 * computation is injected with `RuntimeLaws.withComputation`, exactly as [PerfCatalog.b7] injects its.
 */
object ComputationFuzz {

    private const val GROUP = "/Calc"
    private val OPS = listOf("+", "-", "*")   // no division — keeps every value scale-0 and dodges div-by-zero clearing

    /** Build a random computation-chain model for [modelSeed] as a COMPUTE [PerfScenario]. */
    fun model(modelSeed: Int): PerfScenario {
        val rnd = Random(modelSeed.toLong())
        val baseCount = rnd.nextInt(2, 5)        // 2..4 base inputs
        val compCount = rnd.nextInt(3, 7)        // 3..6 chained computed fields
        val baseNames = (0 until baseCount).map { "B$it" }
        val compNames = (0 until compCount).map { "C$it" }
        // Each C{i}'s operation, generated so C{i} (i>=1) always references C{i-1} → a guaranteed cascade chain.
        val ops = (0 until compCount).map { i -> expression(i, baseNames, rnd) }

        val structureJson = DocumentModelDraft.named("calc").locale("en_US")
            .rootGroup("Calc", Consumer { g ->
                baseNames.forEach { b -> g.numberField(b, Consumer<NumberFieldBuilder> { it.fractionDigits(0) }) }
                compNames.forEach { c -> g.numberField(c, Consumer<NumberFieldBuilder> { it.fractionDigits(0) }) }
            })
            .build().toJson()
        val kernelModel = compNames.indices.fold(structureJson) { m, i ->
            RuntimeLaws.withComputation(m, GROUP, "Comp_C$i", "../C$i", ops[i])
        }

        val fields = (baseNames + compNames).map { FieldDef("$GROUP/$it", Kind.NUMBER, scale = 0) }
        val interpModel = ModelDef(
            fields, emptyList(),
            computations = compNames.indices.map { i -> ComputationDef("$GROUP/C$i", GROUP, ops[i]) },
        )
        val gen = SeedGenerator(interpModel)
        val computed = compNames.map { "$GROUP/$it" }.toSet()
        // Base fields: a random int (or empty ~30% → empty-as-0 in the computation). Computed fields: left empty
        // (the seed never pre-fills a computed field, so every one is a genuine compute change on both engines).
        val values = ValueStrategy { f, _, r ->
            when {
                f.path in computed -> null
                r.nextDouble() < 0.3 -> null
                else -> r.nextInt(0, 50).toString()
            }
        }
        return PerfScenario("compfuzz#$modelSeed b=$baseCount c=$compCount", kernelModel, interpModel, PerfOp.COMPUTE) {
            sink, _, variant -> gen.populate(sink, seed = variant.toLong(), rowsOf = { _, _, _ -> 0 }, value = values)
        }
    }

    /**
     * The operation for `C{i}`: a small chain `op0 OP op1 [OP op2]` over operands drawn from the base fields,
     * the EARLIER computed fields `C0..C{i-1}` (acyclic by construction), and small constants. For `i>=1` the
     * first operand is forced to be `C{i-1}` so the dependency chain is never broken — the cascade is the point.
     */
    private fun expression(i: Int, baseNames: List<String>, rnd: Random): String {
        val avail = baseNames + (0 until i).map { "C$it" }
        val parts = mutableListOf<String>()
        parts += if (i >= 1) "[C${i - 1}]" else operand(avail, rnd)
        repeat(1 + rnd.nextInt(2)) { parts += "${OPS[rnd.nextInt(OPS.size)]} ${operand(avail, rnd)}" }
        return "{ " + parts.joinToString(" ") + " }"
    }

    /** One operand: a field reference `[Name]` (~70%) or a small constant 1..5 (~30%). */
    private fun operand(avail: List<String>, rnd: Random): String =
        if (rnd.nextDouble() < 0.3) (1 + rnd.nextInt(5)).toString() else "[${avail[rnd.nextInt(avail.size)]}]"
}
