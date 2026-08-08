package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.FuzzDiagnostics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * **Harness G — the sibling-group differential.** Two sibling repeatable groups (`A` ⊥ `B` under a common
 * non-repeatable root) — the shape NO other fuzz net produces (they do nested chains or one fixed model), and
 * the shape the silent parallel-iteration bug hid in. [SiblingGroupFuzz] generates it; this test asserts the
 * two regimes:
 *
 *  1. [supportedSiblingRulesAgreeWithKernel] — rules whose iteration is a single group, or whose cross-sibling
 *     reference is **aggregated** (a star collapses the sibling), agree with the kernel at the full-message
 *     level. This is the multi-sibling coverage (independent iteration of two repetition lists, cross-sibling
 *     aggregates) the validation nets lacked.
 *  2. [parallelIterationAgreesWithKernel] — a per-row reference into BOTH siblings (the index-keyed outer join)
 *     is iterated jointly over the union of the shared index values; across MANY fuzzed documents the
 *     interpreter must agree with the kernel at the full-message level (matched rows, the synthetic `-5` side,
 *     polarity, empty-as-0, AND the invalid-index 3VL corner — the strategy injects occasional duplicate/empty
 *     index values, KERNEL-FINDINGS §9).
 */
@Tag("fuzz")
class SiblingGroupFuzzDiffTest {

    @Test
    fun supportedSiblingRulesAgreeWithKernel() {
        val failures = buildList {
            for (m in 0 until MODELS) {
                val scenario = SiblingGroupFuzz.supported(m)
                for (d in 0 until DOCS) {
                    if (!FuzzDiagnostics.signaturesAgree(scenario, d)) add(report(scenario, d))
                }
            }
        }
        assertTrue(failures.isEmpty()) {
            "sibling-group fuzz: interpreter diverged from the kernel on ${failures.size} (model, doc) pairs:\n" +
                failures.take(15).joinToString("\n")
        }
    }

    @Test
    fun parallelIterationAgreesWithKernel() {
        val failures = mutableListOf<String>()
        val silentCases = mutableListOf<String>()
        for (m in 0 until SiblingGroupFuzz.PARALLEL_CASES) {
            val case = SiblingGroupFuzz.parallelCase(m)
            var fired = false
            for (d in 0 until PARALLEL_DOCS) {
                if (!fired && case.code in FuzzDiagnostics.kernelCodes(case.scenario, d)) fired = true
                if (!FuzzDiagnostics.signaturesAgree(case.scenario, d)) failures += report(case.scenario, d)
            }
            if (!fired) silentCases += case.scenario.name
        }
        assertTrue(failures.isEmpty()) {
            "parallel-iteration fuzz: interpreter diverged from the kernel on ${failures.size} (model, doc) pairs:\n" +
                failures.take(15).joinToString("\n")
        }
        // Sanity, PER CASE: a shared flag lets one firing case vouch for a sibling that is agreeing about silence.
        assertTrue(silentCases.isEmpty()) {
            "the kernel never fired these parallel-iteration cases on any document, so their agreement is " +
                "vacuous: $silentCases"
        }
    }

    /**
     * The generator half of the partial regime, separated from the differential exactly as the partial net
     * separates its own two methods: a red here is a GENERATOR regression (the seed matrix stopped producing a
     * shape), a red below is an INTERPRETER divergence. Cheap — no engine runs, only the generator.
     */
    @Test
    fun theParallelSeedMatrixReachesEveryDiscriminatingRelevanceShape() {
        val coverage = buildSet {
            for (c in 0 until SiblingGroupFuzz.PARALLEL_CASES) {
                val scenario = SiblingGroupFuzz.parallelCase(c).scenario
                for (d in 0 until PARTIAL_DOCS) for (r in 0 until PARTIAL_REL_SEEDS) {
                    val view = FuzzDiagnostics.relevantView(scenario, d, FuzzDiagnostics.partialRelevantSeed(d, r))
                    addAll(SiblingGroupFuzz.parallelRelevanceShapes(view))
                }
            }
        }

        assertEquals(
            SiblingGroupFuzz.ParallelRelevanceShape.entries.toSet(), coverage,
            "the partial parallel regime must separate a row's INDEX cell from its payload in both directions " +
                "and reach one-sided and two-sided joins; document values cannot cover a shape the relevant-set " +
                "generator never produces, so agreement below would be vacuous without this",
        )
    }

    /**
     * **The cell no other net reaches.** The only other partial net's model is a nested chain
     * (`Lines ⊃ Parts`), and `IterationScopes.prepare` excludes an ancestor group from the parallel candidates —
     * so that topology can never be planned as a parallel iteration, at any seed count. Sibling topology was
     * fuzzed only under FULL validation, partial relevance only over a nested chain, and the index-keyed join
     * under partial relevance fell between them ([IF269] was found by hand, not by a net).
     */
    @Test
    fun parallelIterationAgreesWithKernelUnderPartialRelevance() {
        val failures = mutableListOf<String>()
        val silentCases = mutableListOf<String>()
        for (c in 0 until SiblingGroupFuzz.PARALLEL_CASES) {
            val case = SiblingGroupFuzz.parallelCase(c)
            var fired = false
            for (d in 0 until PARTIAL_DOCS) for (r in 0 until PARTIAL_REL_SEEDS) {
                val rel = FuzzDiagnostics.partialRelevantSeed(d, r)
                // Short-circuited PER CASE: once THIS case's rule has fired under some relevant set its oracle is
                // satisfied, and the extra kernel run is skipped for its remaining combinations. Sharing one flag
                // across cases would let a firing case vouch for a sibling that never fires at all.
                if (!fired && case.code in FuzzDiagnostics.kernelPartialCodes(case.scenario, d, rel)) fired = true
                if (!FuzzDiagnostics.partialSignaturesAgree(case.scenario, d, rel)) {
                    failures += "${case.scenario.name} :: " + FuzzDiagnostics.partialDivergence(case.scenario, d, rel)
                }
            }
            if (!fired) silentCases += case.scenario.name
        }
        assertTrue(failures.isEmpty()) {
            "partial parallel-iteration fuzz: interpreter diverged from the kernel on ${failures.size} " +
                "(case, doc, relevant) combinations:\n" + failures.take(5).joinToString("\n")
        }
        assertTrue(silentCases.isEmpty()) {
            "the kernel never fired these parallel-iteration cases under ANY relevant set, so they are agreeing " +
                "about silence, which no relevant-set bug could disturb: $silentCases"
        }
    }

    /**
     * A diverging seed, WITH the document that produced it. [FuzzDiagnostics.signatureDivergence] names which
     * engine pair split but not what was fed in, and reading a sibling-join divergence means seeing the index
     * values — chasing [IF270] otherwise took a hand-written probe just to print the placements.
     */
    private fun report(scenario: PerfScenario, docSeed: Int): String =
        "${scenario.name} docSeed=$docSeed :: ${FuzzDiagnostics.signatureDivergence(scenario, docSeed)}\n" +
            FuzzDiagnostics.placementDump(scenario, docSeed)

    private companion object {
        const val MODELS = 40
        const val DOCS = 5

        /**
         * The parallel regime spends its budget on DOCUMENTS, not on the first loop. [SiblingGroupFuzz.supported]
         * genuinely varies with its argument (`t = 1 + modelSeed % 90`), so 40 model seeds there are 40 models;
         * [SiblingGroupFuzz.parallelCase] has only [SiblingGroupFuzz.PARALLEL_CASES] shapes and the generated
         * document depends on the DOC seed alone — so the former `MODELS × DOCS` loop ran 200 times over 10
         * unique comparisons and just 5 distinct documents. Same shape, 8× the documents, 40 % of the runs.
         */
        const val PARALLEL_DOCS = 40

        /** The partial regime's budget goes on DOCUMENTS × RELEVANT SETS, the two axes that vary its outcome —
         *  12 × 10 × [SiblingGroupFuzz.PARALLEL_CASES] = 240 tri-engine partial validations. */
        const val PARTIAL_DOCS = 12
        const val PARTIAL_REL_SEEDS = 10
    }
}
