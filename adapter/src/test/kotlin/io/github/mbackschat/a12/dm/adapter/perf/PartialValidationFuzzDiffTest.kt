package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.FuzzDiagnostics
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The **partial-validation** differential fuzz net — the relevant-set twin of [InterpreterKernelFuzzTest]. For
 * many random-but-VALID documents generated from one model, AND a random subset of relevant entities per run,
 * assert the interpreter's `validatePart` produces the IDENTICAL fired-message signatures (`code|type|pointer`)
 * as the kernel's. This is the standing guard for the relevant-set dimension: it sweeps random *combinations* of
 * (document, relevant subset), exercising rule-gating, 3VL suppression, subset aggregates (IF35), global fields
 * (IF37), custom-condition relevance (IF38), and subset uniqueness (IF39) together — where the hand-written
 * `laws/PartialValidation*DiffTest`s pin one case each. KERNEL-FINDINGS "Partial validation".
 *
 * Each (docSeed, relSeed) is deterministic, so a failing combination is exactly reproducible (printed).
 *
 * **Scope ([IG20_AGGREGATES]):** the three model-scope STARRED-AGGREGATE rules (`TP` = sum of `Lines* / Price`,
 * `FF` = first-filled `Lines* / Qty`, `SP` = sum-of-products) are excluded — under *mixed* partial relevance
 * the kernel's per-operator aggregate semantics are subtler than the blanket IF35 gate (tracked as IG20 in
 * INTERPRETER-GAPS-LEDGER §1). The fuzz still guards everything else: rule-gating, 3VL on cross-references, every
 * per-row rule, the formal errors, and uniqueness — all of which this net confirmed agree under partial.
 */
class PartialValidationFuzzDiffTest {

    @Test
    fun randomRelevantSubsetsAgreeWithKernel() {
        val scenario = PerfCatalog.fuzz()
        val diverging = buildList {
            for (docSeed in 0 until DOC_SEEDS) for (relSeed in 0 until REL_SEEDS) {
                if (!FuzzDiagnostics.partialSignaturesAgree(scenario, docSeed, docSeed * 131 + relSeed, IG20_AGGREGATES)) {
                    add(docSeed to (docSeed * 131 + relSeed))
                }
            }
        }
        assertTrue(diverging.isEmpty()) {
            "interpreter validatePart diverged from the kernel on ${diverging.size} (doc,relevant) combinations:\n" +
                diverging.take(15).joinToString("\n") { (d, r) -> FuzzDiagnostics.partialDivergence(scenario, d, r, IG20_AGGREGATES) }
        }
    }

    private companion object {
        const val DOC_SEEDS = 60
        const val REL_SEEDS = 3
        /** IG20: starred aggregates over a repeatable group under mixed partial relevance — not yet kernel-exact. */
        val IG20_AGGREGATES = setOf("TP", "FF", "SP")
    }
}
