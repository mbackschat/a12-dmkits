package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Groovy is the ONLY strategy that splits a rule's condition lines: once a rule reaches
 * `NUM_CONDITIONLINES_PER_METHOD_GROOVY = 50` atomic conditions, `CodeGenRuleDefinition
 * .getUseClosureForConditionLineCode()` flips and the Groovy template emits bare `tb<i>` declarations plus
 * chunked `conditionLineCode_<n>` closures, where Java emits one straight-line `tb<i> = <cond>;` list.
 * Static-Java has no such mode at any size.
 *
 * That makes a >=50-term rule the only shape where the two kernel strategies lower the SAME rule differently,
 * and nothing in our estate reached it: measured over both real corpora (2 460 rules) the atomic-term count
 * peaks at 21, and the committed corpus carries one rule per model. So the "0 java-static splits" evidence was
 * structurally silent here — the same shape as IF123, one layer up.
 *
 * Each case therefore proves the mode ENGAGED (by reading the emitted Groovy) before comparing outcomes; a
 * runtime result alone cannot distinguish split from straight-line lowering, so without that assertion a green
 * test would not evidence coverage. Kernel truths: KERNEL-INTEGRATION-NOTES "The codegen templates are the
 * kernel's own lowering rules".
 */
class ConditionLineSplitDiffTest {

    private companion object {
        const val CODE = "CLSPLIT"
        const val GROUP = "/Probe"
        /** `Closure conditionLineCode_<n> = ` — `MetaSprachUnterschiedeHelper.GROOVY_CONDITIONLINECODE_CLOSURE_NAME`. */
        const val SPLIT_MARKER = "Closure conditionLineCode_"
        const val THRESHOLD = 50
        /** Two chunks (50 + 10): past the threshold, far below Groovy's own 250-per-condition-boolean ceiling. */
        const val WIDE = 60
        /** Three chunks (50 + 50 + 10) — the only width at which a chunk can be a MIDDLE one. */
        const val WIDER = 110
    }

    /** A model with [WIDER] number fields, so a rule can be an And-chain of up to that many atomic comparisons. */
    private val base = DocumentModelDraft.named("condition-line-split").locale("en_US")
        .rootGroup("Probe") { g -> (1..WIDER).forEach { i -> g.numberField("F$i") { } } }
        .build().toJson()
    private val model = BoundModel.ofJson(base)

    /** `F1 > 0 And F2 > 0 And … And Fn > 0` — [n] atomic conditions, so [n] condition lines. */
    private fun chain(n: Int): Condition =
        (2..n).fold(model.num("$GROUP/F1").gt(0)) { acc, i -> acc.and(model.num("$GROUP/F$i").gt(0)) }

    @Test
    fun `a rule at the threshold splits in groovy and stays straight-line in java`() {
        val atThreshold = kernelModel(chain(THRESHOLD))
        assertTrue(RuntimeLaws.generatedGroovySource(atThreshold).contains(SPLIT_MARKER)) {
            "$THRESHOLD condition lines must engage the Groovy split (the flip is `>= $THRESHOLD`)"
        }
        val java = RuntimeLaws.generatedJavaSource(atThreshold)
        assertTrue(java.contains("tb0")) { "the Java source must really have been generated" }
        assertFalse(java.contains(SPLIT_MARKER)) {
            "static-Java has no condition-line split mode at any size"
        }

        val below = RuntimeLaws.generatedGroovySource(kernelModel(chain(THRESHOLD - 1)))
        // Non-emptiness first: without it this negative control would also pass on a generation that produced
        // nothing at all, which is the vacuous-green this whole test exists to avoid.
        assertTrue(below.contains("tb0")) {
            "the below-threshold Groovy must really have been generated (straight-line `tb0` declaration present)"
        }
        assertFalse(below.contains(SPLIT_MARKER)) {
            "${THRESHOLD - 1} condition lines must stay below the flip — pins the threshold, so the case above is genuinely past it"
        }
    }

    @Test
    fun `all terms satisfied fires identically across both strategies and the interpreter`() {
        agree(chain(WIDE), filled = (1..WIDE).toList(), expected = true,
            label = "every one of $WIDE split condition lines holds")
    }

    @Test
    fun `a veto in a LATER chunk still suppresses the rule`() {
        // Term 55 lands in the second closure chunk (chunks are 50 wide). If chunking lost a later chunk's
        // result, the rule would fire here — this is the case the split could actually break.
        agree(chain(WIDE), filled = (1..WIDE).toList() - 55, expected = false,
            label = "term 55 (second chunk) is unfilled, so the And-chain must not fire")
    }

    @Test
    fun `a veto in the FIRST chunk suppresses the rule`() {
        agree(chain(WIDE), filled = (1..WIDE).toList() - 1, expected = false,
            label = "term 1 (first chunk) is unfilled — the control against the later-chunk case")
    }

    @Test
    fun `a veto in a MIDDLE chunk still suppresses the rule`() {
        // At $WIDER terms the split is 50+50+10, so term 75 sits in a chunk that is neither first nor last —
        // the case a chunk-call loop that emitted only the ends would silently drop.
        agree(chain(WIDER), filled = (1..WIDER).toList() - 75, expected = false,
            label = "term 75 (middle of three chunks) is unfilled, so the And-chain must not fire")
    }

    @Test
    fun `the last term alone decides at the chunk boundary`() {
        // The final term is the one a chunked call chain is most likely to drop.
        agree(chain(WIDE), filled = (1..WIDE).toList() - WIDE, expected = false,
            label = "only the last of $WIDE terms is unfilled")
    }

    private fun kernelModel(condition: Condition): String =
        RuntimeLaws.withRule(base, GROUP, "R", "../F1", CODE, DslRenderer().render(condition))

    private fun agree(condition: Condition, filled: List<Int>, expected: Boolean, label: String) {
        val rendered = DslRenderer().render(condition)
        val kModel = RuntimeLaws.withRule(base, GROUP, "R", "../F1", CODE, rendered)

        val authoring = RuleValidator().check(base, DraftRule(GROUP, "R", "../F1", rendered, CODE, "ERROR"))
        assertTrue(authoring.ok()) {
            "$label — the rendered witness must pass the kernel authoring gate: ${authoring.diagnostics()}"
        }
        assertTrue(RuntimeLaws.generatedGroovySource(kModel).contains(SPLIT_MARKER)) {
            "$label — the case must actually exercise the Groovy split, else this proves nothing"
        }

        val kDoc = RuntimeLaws.doc().group(GROUP, 1)
        filled.forEach { kDoc.field("$GROUP/F$it", "7", 1, 1) }
        val groovy = RuntimeLaws.messages(kModel, kDoc).any { it.code() == CODE }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).contains(CODE)

        val definition = ModelLoader.load(kModel)
        val iDoc = definition.newDocument().group(GROUP, 1)
        filled.forEach { iDoc.field("$GROUP/F$it", "7", 1, 1) }
        val interpreter = DmInterpreter(definition).validateFull(iDoc).any { it.code == CODE }

        assertEquals(groovy, javaStatic, "$label — split-Groovy vs straight-line static-Java")
        assertEquals(groovy, interpreter, "$label — kernel vs interpreter")
        assertEquals(expected, groovy, "$label — groovy-dynamic anchor")
    }
}
