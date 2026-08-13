package io.github.mbackschat.a12.dm.adapter.perf

import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * **Timing coverage for the [DmInterpreter] evaluation entry points the scenario sweep cannot reach.**
 *
 * [PerfOp] models exactly two operations — `validate` and `compute` — so every existing gate and benchmark
 * times one of those two. The other four public evaluation entry points had **no timing test at all**, and the
 * gap is not academic: partial validation was quadratic for as long as it existed whenever the caller's
 * relevant set grew with the document, and the scenario sweep could not see it because no scenario called
 * `validatePart`. These four are the same class of blind spot one entry point over.
 *
 * Each guard reuses a **reviewed [PerfCatalog] scenario** rather than minting a model, so the document and both
 * engine models are shared with the sweep and cannot drift from it. Two scenarios carry all four:
 *
 * | entry point | scenario | why it is the fair kernel counterpart |
 * |---|---|---|
 * | `testRule` / `evalCandidate` | [PerfCatalog.b1] — exactly ONE rule | the kernel's `validateFull` over a one-rule model evaluates that same rule over the same rows |
 * | `computeField` / `applyComputations` | [PerfCatalog.b7] — exactly ONE computation | the kernel's `compute()` over a one-computation model computes that same field |
 *
 * The one-construct property is what keeps the comparison honest: the interpreter's entry point does a slice of
 * the work, and on these models the kernel's whole-document call is the same rule or the same computation. It is
 * asserted, not assumed — [singleRuleScenario]/[singleComputationScenario] fail if a scenario ever grows a
 * second construct. It is not an identity, and should not be read as one: the kernel's whole-document call also
 * formally validates every field in the model, so a model carrying many fields unrelated to the rule would
 * charge the kernel work the focused call skips. These two scenarios are narrow, which is why the margins below
 * are reported as measurements of THEM rather than as a general statement about the entry points.
 *
 * Correctness before timing, in two layers: [PerfHarness.preflight] refuses to time an unsupported construct,
 * and each test cross-checks its entry point against the kernel on the same document before the clock starts.
 * Full signature agreement for these two scenarios is separately locked by their inventory rows (same model,
 * same document — the entry points differ only in which slice of the result they return).
 *
 * The bar and the growth check are [KernelAdvantage]; absolute numbers are printed for the record and are not a
 * release bar (that needs IF137 certification).
 */
@Tag("perf")
class EntryPointPerfTest {

    @Test
    @TimesInterpreterEntryPoint("testRule", String::class, Document::class, String::class)
    fun testRuleBeatsTheKernelAndScales() {
        val rig = Rig(singleRuleScenario())
        val rulePath = rig.scenario.interpModel.rules.single().path

        assertEquals(
            rig.kernel.validationMessageCount(rig.kernelDoc(SMALL_ROWS)) > 0,
            rig.interpreter.testRule(rulePath, rig.interpDoc(SMALL_ROWS)).verdict == Verdict.FIRED,
            "testRule and the kernel must agree on whether the rule fires before either is timed",
        )
        rig.measure(
            "testRule", SMALL_ROWS, LARGE_ROWS,
            interpreter = { interp, doc -> interp.testRule(rulePath, doc) },
            kernel = { engine, doc -> engine.validationMessageCount(doc) },
        )
    }

    @Test
    @TimesInterpreterEntryPoint("evalCandidate", RuleDef::class, Document::class, String::class)
    fun evalCandidateBeatsTheKernelAndScales() {
        val rig = Rig(singleRuleScenario())
        // The SAME rule, injected as a non-stored candidate — the `model eval --condition` shape. It shares
        // `evalRule` with testRule but skips the stored-rule lookup, so a lookup that scanned the document
        // (rather than the rule list) would show up as a gap between these two numbers, not as a failure here.
        val candidate = rig.scenario.interpModel.rules.single()

        assertEquals(
            rig.kernel.validationMessageCount(rig.kernelDoc(SMALL_ROWS)) > 0,
            rig.interpreter.evalCandidate(candidate, rig.interpDoc(SMALL_ROWS)).verdict == Verdict.FIRED,
            "evalCandidate and the kernel must agree on whether the candidate fires before either is timed",
        )
        rig.measure(
            "evalCandidate", SMALL_ROWS, LARGE_ROWS,
            interpreter = { interp, doc -> interp.evalCandidate(candidate, doc) },
            kernel = { engine, doc -> engine.validationMessageCount(doc) },
        )
    }

    @Test
    @TimesInterpreterEntryPoint("computeField", String::class, Document::class, String::class)
    fun computeFieldBeatsTheKernelAndScales() {
        val rig = Rig(singleComputationScenario())
        val target = rig.scenario.interpModel.computations.single().targetPath

        // Agreement, not merely non-emptiness: a wrong-but-nonempty result would otherwise be timed happily.
        // Counts rather than full signatures because the interpreter reports EVERY computed instance while the
        // kernel reports its change projection, and on this scenario the seed leaves the target empty so every
        // instance is a change — a shape assertion the count check would expose if it ever stopped holding.
        val computed = rig.interpreter.computeField(target, rig.interpDoc(SMALL_NESTED)).size
        assertEquals(
            rig.kernel.computationCount(rig.kernelDoc(SMALL_NESTED)), computed,
            "computeField and the kernel must compute the same number of instances before either is timed",
        )
        assertTrue(computed > 0) { "nothing was computed — timing this would measure an empty run" }
        rig.measure(
            "computeField", SMALL_NESTED, LARGE_NESTED,
            interpreter = { interp, doc -> interp.computeField(target, doc) },
            kernel = { engine, doc -> engine.computationCount(doc) },
        )
    }

    @Test
    @TimesInterpreterEntryPoint("applyComputations", Document::class, String::class)
    fun applyComputationsBeatsTheKernelAndScales() {
        val rig = Rig(singleComputationScenario())
        val target = rig.scenario.interpModel.computations.single().targetPath

        // The apply step rebuilds the whole document, so its risk is the rebuild, not the compute: a builder
        // that re-scanned its placements per applied cell would be O(cells²) while `compute` alone stayed linear.
        // A no-op apply would rebuild nothing and time as fast as you like, so require it to actually write.
        val source = rig.interpDoc(SMALL_NESTED)
        val applied = rig.interpreter.applyComputations(source)
        // Keyed by coordinates, not zipped: this scenario's seed leaves the computed field EMPTY, so the source
        // has no valued cell at that path at all and a positional zip pairs 150 applied cells against nothing.
        // An absent source cell is a written cell, which is exactly what the count has to say.
        fun computedCells(doc: Document): Map<List<Int>, String> =
            doc.valuedFields().filter { it.path == target }.associate { it.reps to it.raw }
        val before = computedCells(source)
        val changed = computedCells(applied).count { (reps, value) -> before[reps] != value }
        assertEquals(
            rig.kernel.computationCount(rig.kernelDoc(SMALL_NESTED)), changed,
            "applyComputations must write exactly the cells the kernel's compute() reports as changed; a " +
                "no-op rebuild, or a rebuild that writes the wrong number of cells, would otherwise be timed",
        )
        rig.measure(
            "applyComputations", SMALL_NESTED, LARGE_NESTED,
            interpreter = { interp, doc -> interp.applyComputations(doc) },
            kernel = { engine, doc -> engine.computeAndApply(doc) },
        )
    }

    /** A reviewed scenario bound to both engines, with a document builder per scale. */
    private class Rig(val scenario: PerfScenario) {
        val interpreter: DmInterpreter = scenario.interpreterFactory(scenario.interpModel)
        val kernel: RuntimeLaws.KernelEngine = RuntimeLaws.kernelEngine(scenario.kernelModel)

        fun interpDoc(n: Int): Document =
            scenario.interpModel.newDocument().also { scenario.instance(it.asSink(), n, 0) }

        /** Built through the V1→V2 conversion once, outside every timed region (V2 is immutable, so reuse is safe). */
        fun kernelDoc(n: Int): DocumentV2 =
            kernel.instance(RuntimeLaws.doc().also { scenario.instance(KernelDocSink(it), n, 0) })

        fun measure(
            label: String,
            small: Int,
            large: Int,
            interpreter: (DmInterpreter, Document) -> Unit,
            kernel: (RuntimeLaws.KernelEngine, DocumentV2) -> Unit,
        ) {
            PerfHarness.preflight(scenario, large)
            KernelAdvantage.assertBeatsKernelAndScales(
                label = label,
                smallN = small,
                largeN = large,
                identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            ) { n ->
                val iDoc = interpDoc(n)
                val kDoc = kernelDoc(n)
                KernelAdvantage.read(
                    n,
                    interpreter = { interpreter(this.interpreter, iDoc) },
                    kernel = { kernel(this.kernel, kDoc) },
                )
            }
        }
    }

    private companion object {
        /** B1's flat Line rows. 4× apart, so the growth reading is the same shape the sweep's gates use. */
        const val SMALL_ROWS = 500
        const val LARGE_ROWS = 2_000

        /** B7 is nested (×[PerfCatalog.SUBS_PER_LINE] Sub rows per Line), so the Line counts are smaller. */
        const val SMALL_NESTED = 150
        const val LARGE_NESTED = 600

        /**
         * B1 with its one-rule property CHECKED. The fairness of comparing `testRule` against the kernel's
         * whole-document `validateFull` rests entirely on the model carrying exactly one rule; if the scenario
         * ever gains a second, this comparison silently starts charging the kernel for extra work.
         */
        fun singleRuleScenario(): PerfScenario = PerfCatalog.b1().also {
            check(it.interpModel.rules.size == 1 && it.interpModel.computations.isEmpty()) {
                "the single-rule entry-point comparison needs a one-rule model; B1 now has " +
                    "${it.interpModel.rules.size} rule(s) and ${it.interpModel.computations.size} computation(s)"
            }
        }

        /** B7 with the same property checked for the compute side. */
        fun singleComputationScenario(): PerfScenario = PerfCatalog.b7().also {
            check(it.interpModel.computations.size == 1 && it.interpModel.rules.isEmpty()) {
                "the single-computation entry-point comparison needs a one-computation model; B7 now has " +
                    "${it.interpModel.computations.size} computation(s) and ${it.interpModel.rules.size} rule(s)"
            }
        }
    }
}
