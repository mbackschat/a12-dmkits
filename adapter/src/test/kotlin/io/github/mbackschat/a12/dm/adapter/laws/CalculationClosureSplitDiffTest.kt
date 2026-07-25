package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The computation twin of [ConditionLineSplitDiffTest]: Groovy is the only strategy that splits a computed
 * field's alternatives into closures, and the mode is **size-gated**, not unconditional.
 * `CodeGenCalculatedField.getUseClosureForCalculationCode()` returns
 * `calculationAlternatives.size() >= NUM_CALCULATIONS_PER_METHOD_GROOVY` (**10**); only inside that arm does the
 * Groovy template interpolate `t.calculationSplitFunctionCode`, whose getter flips each alternative's own flag
 * as a **side effect**. A computed field with fewer than 10 alternatives takes the plain
 * `t.calculationCode` route and no closure is emitted anywhere.
 *
 * (That side-effecting getter is why the mode reads as unconditional if the `forEach(… setUseClosure…(true))`
 * statement is read without its enclosing method — it is reached only when the gate already passed.)
 *
 * The split chunk calls are joined with **` || `** and each chunk ends `return false;`, so the chunks
 * short-circuit in order — which is exactly how the unsplit route's "first alternative whose precondition
 * holds wins" survives chunking. A winner in a later chunk must still win, and a winner in an earlier chunk
 * must stop evaluation before the later chunk.
 *
 * Neither corpus reaches the gate: measured over 316 computations the alternative count peaks at **7**
 * (workspaces) and **1** (kernel-mm), so "646 computations compared" evidenced nothing about this mode. Unlike
 * the 50-term condition chain, 10 alternatives is a realistic authored shape. Kernel truths:
 * KERNEL-INTEGRATION-NOTES "The codegen templates are the kernel's own lowering rules".
 */
class CalculationClosureSplitDiffTest {

    private companion object {
        const val GROUP = "/Probe"
        const val SEL = "/Probe/Sel"
        const val TOTAL = "/Probe/Total"
        /** `Closure<Boolean> calculationCode_<n> = ` — `MetaSprachUnterschiedeHelper.GROOVY_CALCULATIONCODE_CLOSURE_NAME`. */
        const val SPLIT_MARKER = "Closure<Boolean> calculationCode_"
        const val GATE = 10
        /** Two chunks (10 + 2), so a winner can sit in a later chunk. */
        const val WIDE = 12
    }

    private val base = DocumentModelDraft.named("calculation-closure-split").locale("en_US")
        .rootGroup("Probe") { g ->
            g.numberField("Sel") { it.fractionDigits(0, 0) }
            g.numberField("Total") { it.fractionDigits(0, 0) }
        }.build().toJson()

    /** [n] alternatives; alternative `i` fires on `Sel == i` and assigns `i * 100`, so the winner is selectable. */
    private fun model(n: Int): String = RuntimeLaws.withTieredComputation(
        base, GROUP, "TotalComp", "../Total", null,
        (1..n).map { RuntimeLaws.CompAlt("[Sel] == $it", "${it * 100}") },
    )

    @Test
    fun `a computed field at the gate splits in groovy and stays plain in java`() {
        val atGate = RuntimeLaws.generatedGroovySource(model(GATE))
        assertTrue(atGate.contains(SPLIT_MARKER)) {
            "$GATE alternatives must engage the Groovy calculation split (the flip is `>= $GATE`)"
        }

        val java = RuntimeLaws.generatedJavaSource(model(GATE))
        assertTrue(java.contains("berechne")) { "the Java source must really have been generated" }
        assertFalse(java.contains(SPLIT_MARKER)) {
            "static-Java has no calculation-closure mode at any alternative count"
        }

        val below = RuntimeLaws.generatedGroovySource(model(GATE - 1))
        assertTrue(below.contains("berechne")) {
            "the below-gate Groovy must really have been generated, else this control is vacuous"
        }
        assertFalse(below.contains(SPLIT_MARKER)) {
            "${GATE - 1} alternatives must stay below the flip — pins the gate, so the case above is genuinely past it"
        }
    }

    @Test
    fun `a winner in the FIRST chunk wins across both strategies and the interpreter`() {
        agree(selector = 3, expected = "VALUE|300",
            label = "alternative 3 (first chunk of $WIDE) holds; later chunks must not override it")
    }

    @Test
    fun `a winner in a LATER chunk still wins`() {
        // Alternative 11 sits in the second chunk. If the ` || `-joined chunk calls dropped a later chunk, the
        // computation would produce nothing here — this is the case the split could actually break.
        agree(selector = 11, expected = "VALUE|1100",
            label = "alternative 11 (second chunk) is the only one whose precondition holds")
    }

    @Test
    fun `the last alternative still wins at the chunk boundary`() {
        agree(selector = WIDE, expected = "VALUE|${WIDE * 100}",
            label = "the final alternative of $WIDE is the winner")
    }

    @Test
    fun `no alternative holding computes nothing`() {
        agree(selector = 99, expected = "CLEARED",
            label = "no precondition holds across either chunk, so the target clears")
    }

    private fun agree(selector: Int, expected: String, label: String) {
        val kModel = model(WIDE)
        assertTrue(RuntimeLaws.generatedGroovySource(kModel).contains(SPLIT_MARKER)) {
            "$label — the case must actually exercise the Groovy calculation split, else this proves nothing"
        }
        val def = ModelLoader.load(kModel)

        fun seed(k: RuntimeLaws.Doc) = k.group(GROUP, 1).field(SEL, "$selector", 1, 1).field(TOTAL, "999", 1, 1)
        val groovy = RuntimeLaws.compute(kModel, seed(RuntimeLaws.doc())).firstOrNull { it.path() == TOTAL }
            .let { c ->
                when {
                    c == null -> "NONE"
                    c.cleared() -> "CLEARED"
                    c.errored() -> "ERRORED"
                    else -> "VALUE|${c.value().orElse("")}"
                }
            }

        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(seed(RuntimeLaws.doc())))
            .firstOrNull { it.startsWith("$TOTAL|") }
            ?.substringAfter("$TOTAL|")
            ?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"

        val iDoc = def.newDocument().group(GROUP, 1).field(SEL, "$selector", 1, 1).field(TOTAL, "999", 1, 1)
        val interp = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == TOTAL }
            .let { c ->
                when (c?.outcome) {
                    null -> "NONE"
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED"
                    ComputeOutcome.VALUE -> "VALUE|${c.value}"
                }
            }

        assertEquals(groovy, javaStatic, "$label — split-Groovy vs unsplit static-Java")
        assertEquals(groovy, interp, "$label — kernel vs interpreter")
        assertEquals(expected, groovy, "$label — groovy-dynamic anchor")
    }
}
