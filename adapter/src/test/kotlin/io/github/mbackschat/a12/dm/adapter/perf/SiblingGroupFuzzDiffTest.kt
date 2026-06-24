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
 *  2. [parallelIterationAgreesWithKernel] — a per-row reference into BOTH siblings (the index-keyed outer join)
 *     is iterated jointly over the union of the shared index values; across MANY fuzzed documents the
 *     interpreter must agree with the kernel at the full-message level (matched rows, the synthetic `-5` side,
 *     polarity, empty-as-0, AND the invalid-index 3VL corner — the strategy injects occasional duplicate/empty
 *     index values, KERNEL-FINDINGS §9).
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
    fun parallelIterationAgreesWithKernel() {
        val failures = mutableListOf<String>()
        var kernelEverFired = false
        for (m in 0 until MODELS) {
            val case = SiblingGroupFuzz.parallelCase(m)
            for (d in 0 until DOCS) {
                if (case.code in FuzzDiagnostics.kernelCodes(case.scenario, d)) kernelEverFired = true
                if (!FuzzDiagnostics.signaturesAgree(case.scenario, d)) {
                    failures += "${case.scenario.name} docSeed=$d :: " + FuzzDiagnostics.signatureDivergence(case.scenario, d)
                }
            }
        }
        assertTrue(failures.isEmpty()) {
            "parallel-iteration fuzz: interpreter diverged from the kernel on ${failures.size} (model, doc) pairs:\n" +
                failures.take(15).joinToString("\n")
        }
        // Sanity: the kernel really DOES fire these rules — otherwise the agreement would be vacuous.
        assertTrue(kernelEverFired) { "kernel never fired any parallel-iteration code — the cases aren't exercising the join" }
    }

    private companion object {
        const val MODELS = 40
        const val DOCS = 5
    }
}
