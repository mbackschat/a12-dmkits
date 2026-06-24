package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.FuzzDiagnostics
import org.junit.jupiter.api.Assertions.assertTrue
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
 *  2. [parallelIterationFailsLoudNeverSilentlyWrong] — a per-row reference into BOTH siblings (the index-keyed
 *     outer join) is not yet modeled, so across MANY fuzzed documents the interpreter must report the rule
 *     **unsupported** and emit NO message of that code, while the kernel evaluates it (fires the code on at
 *     least some documents). This hardens the loud-not-wrong contract beyond the single hand-picked
 *     `ParallelIterationGapTest` — and becomes the acceptance gate when the join is implemented (it will flip
 *     to an interp == kernel assertion).
 */
class SiblingGroupFuzzDiffTest {

    @Test
    fun supportedSiblingRulesAgreeWithKernel() {
        val failures = buildList {
            for (m in 0 until MODELS) {
                val scenario = SiblingGroupFuzz.supported(m)
                for (d in 0 until DOCS) {
                    if (!FuzzDiagnostics.signaturesAgree(scenario, d)) {
                        add("${scenario.name} docSeed=$d :: " + FuzzDiagnostics.signatureDivergence(scenario, d))
                    }
                }
            }
        }
        assertTrue(failures.isEmpty()) {
            "sibling-group fuzz: interpreter diverged from the kernel on ${failures.size} (model, doc) pairs:\n" +
                failures.take(15).joinToString("\n")
        }
    }

    @Test
    fun parallelIterationFailsLoudNeverSilentlyWrong() {
        val problems = mutableListOf<String>()
        var kernelEverFired = false
        for (m in 0 until MODELS) {
            val case = SiblingGroupFuzz.parallelCase(m)
            for (d in 0 until DOCS) {
                if (case.code in FuzzDiagnostics.kernelCodes(case.scenario, d)) kernelEverFired = true
                val outcome = FuzzDiagnostics.interpValidation(case.scenario, d)
                // The interpreter must NEVER emit a (necessarily wrong/partial) message for the unmodeled join …
                if (outcome.messages.any { it.code == case.code }) {
                    problems += "${case.scenario.name} docSeed=$d :: interp SILENTLY emitted ${case.code} (must decline)"
                }
                // … and must report the rule unsupported (a loud, honest "not modeled"), not just stay silent.
                if (outcome.unsupported.none { it.rulePath == case.rulePath && it.reason.contains("parallel iteration") }) {
                    problems += "${case.scenario.name} docSeed=$d :: interp did NOT report ${case.rulePath} unsupported: ${outcome.unsupported}"
                }
            }
        }
        assertTrue(problems.isEmpty()) { "parallel-iteration loud-not-wrong violated:\n" + problems.take(15).joinToString("\n") }
        // Sanity: the kernel really DOES evaluate (and fire) these rules — otherwise we'd be "declining" a no-op.
        assertTrue(kernelEverFired) { "kernel never fired any parallel-iteration code — the cases aren't exercising the join" }
    }

    private companion object {
        const val MODELS = 40
        const val DOCS = 5
    }
}
