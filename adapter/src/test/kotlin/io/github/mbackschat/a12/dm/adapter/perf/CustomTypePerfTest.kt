package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Perf guard for the declarative custom-field-type path (`--predefined-types` / `predefinedTypes` registry, G30 /
 * IF26). Validating a `CustomFieldType` cell runs `PredefinedType.accepts(value)` **per valued cell** on the
 * formal-error HOT path — it MUST stay a monomorphic, JIT-inlined call on the concrete `PredefinedType`, NOT a
 * per-cell virtual/megamorphic dispatch or a per-cell allocation. This is the standing
 * [INTERPRETER-PERFORMANCE.md] "the hot path stays a monomorphic concrete call" rule made into a regression
 * gate: IF44's code-based field-type SPI is deliberately additive; a naive "route every field type through one
 * `fun interface`" refactor would deoptimize the declarative path — and this test trips.
 *
 * Two guards (the [CharsetPerfTest] sibling pattern — pure engine, no kernel, JVM-only, measured as
 * current-thread CPU time so another Gradle fork's preemption cannot manufacture growth):
 *  1. **Within a factor of plain-string-with-the-same-pattern** — a custom-typed cell and a plain STRING cell
 *     carrying the identical regex do the same regex match; the custom path adds only the registry lookup +
 *     `accepts` dispatch. So it must stay within a small factor. This catches the GROSS deopts a careless SPI
 *     introduction would cause — a **per-cell allocation** (e.g. boxing a result object instead of returning a
 *     primitive), **megamorphic dispatch**, or a per-cell extra pass — not a single inlined-away virtual call
 *     (which is within timing noise and not a real perf concern).
 *  2. **Sub-quadratic in row count** — validating 4× the rows must stay ~linear (≈4×), never ≈16×; catches an
 *     O(N²) regression in the per-cell custom-type machinery.
 */
@Tag("perf")
class CustomTypePerfTest {

    private val s = "/C/S"
    private val customDef = ModelDef(listOf(
        FieldDef(s, Kind.STRING, customType = CustomFieldTypeDeclaration("Iban")),
    ),
        emptyList(), declaredReps = mapOf("/C" to MAX))
    private val stringDef = ModelDef(listOf(FieldDef(s, Kind.STRING, pattern = IBAN)),
        emptyList(), declaredReps = mapOf("/C" to MAX))
    private val registry = mapOf("Iban" to PredefinedType(minLength = 15, maxLength = 34, pattern = IBAN))

    /**
     * A document of [n] custom-typed cells, all carrying a valid IBAN (so `accepts` runs the full regex per cell).
     *
     * `/C/S` has TWO parts, so a cell carries two coordinates: the repeatable `/C` row, then the field's own.
     * This used to pass three, which the document accepted and the house pointer renderer then ignored — so all
     * [n] cells rendered as the single pointer `/C[1]/S`. Typing the message channel made that unrepresentable.
     */
    private fun doc(n: Int): Document {
        val d = customDef.newDocument()   // kinds are STRING for both defs → one doc serves both
        for (i in 1..n) d.field(s, "DE89370400440532013000", i, 1)
        return d
    }

    @Test fun customTypeValidationIsWithinAFactorOfPlainString() {
        val d = doc(N)
        // Correctness sanity — the timed path is NOT a no-op: a bad value fires customFieldTypeInvalid, and the
        // registered type is actually consulted (nothing reported unsupported).
        val bad = customDef.newDocument().apply { field(s, "NOT-AN-IBAN", 1, 1) }
        val outcome = DmInterpreter(customDef, predefinedTypes = registry).validate(bad)
        assertEquals(1, outcome.messages.size, "the accepts() path must run (a bad IBAN fires once): ${outcome.messages}")
        assertTrue(outcome.unsupported.isEmpty(), "the registered type must be consulted, not reported unsupported: ${outcome.unsupported}")

        fun runCustom() = DmInterpreter(customDef, predefinedTypes = registry).validate(d)
        fun runString() = DmInterpreter(stringDef).validate(d)
        repeat(WARM) { runCustom(); runString() }
        val customMs = PerfHarness.measureCurrentThreadCpu(warmup = 0, iters = 1) {
            repeat(REPEATS) { runCustom() }
        }.medianMs
        val stringMs = PerfHarness.measureCurrentThreadCpu(warmup = 0, iters = 1) {
            repeat(REPEATS) { runString() }
        }.medianMs
        assertTrue(customMs < stringMs * FACTOR + 50.0,
            "custom-type validation (${customMs}ms CPU) must stay within ${FACTOR}× " +
                "plain-string-with-the-same-pattern (${stringMs}ms CPU) " +
                "— a per-cell allocation / megamorphic dispatch on the field-type path regressed (see the monomorphic-hot-path rule)")
    }

    @Test fun customTypeValidationStaysSubQuadratic() {
        val docs = mapOf(N_SMALL to doc(N_SMALL), N_SMALL * 4 to doc(N_SMALL * 4))
        fun run(d: Document) = DmInterpreter(customDef, predefinedTypes = registry).validate(d)
        repeat(WARM) { docs.values.forEach { run(it) } }
        // IF137 paired estimator: interleaved N/4N, median of within-pair ratios (via the shared PerfHarness helper).
        // Re-measured before failing — same reason as the sibling charset guard: it runs unattended on a shared
        // hosted runner in the nightly (routine tier since 2026-08-13; previously untagged and run on every
        // build), and only a real O(N²) regression reproduces across attempts.
        PerfHarness.assertSubQuadraticLane(
            label = "custom-type validation",
            small = N_SMALL,
            identityAt = { n ->
                // `batch` belongs in the identity: it multiplies what one timed block measures, so a key without
                // it would hold 8 calls against an anchor authored for 1. Sampling parameters (warmup, iters) are
                // deliberately NOT here — more samples estimate the same quantity, a bigger batch is a different one.
                PerfHarness.fingerprintedLabel("custom-type-iban", "$IBAN|customDef|rows=$n|batch=$BATCH")
            },
        ) { n ->
            PerfHarness.measureCurrentThreadCpu(warmup = 0, iters = REPEATS) {
                repeat(BATCH) { run(docs.getValue(n)) }
            }.medianMs
        }
    }

    private companion object {
        const val IBAN = "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}"
        const val N = 50_000
        const val N_SMALL = 10_000
        const val MAX = 200_000          // declared repeatability ceiling — above the largest doc, so no over-repetition noise
        const val WARM = 3
        const val REPEATS = 5

        /**
         * Calls per timed block. The small scale measured ~1 ms, which is exactly the anchor's `slackMs` term, so
         * its bar was "under 2 ms" against a 1 ms observation — and its run-to-run spread was the widest in the
         * estate (2.10x at n=10000, 2.86x at n=40000, three sequential isolated gates, 2026-08-13). Batching lifts
         * the recorded median clear of both the slack and the clock's granularity; the growth ratio is a quotient,
         * so a constant factor on both scales cancels exactly.
         */
        const val BATCH = 8
        const val FACTOR = 6             // generous; a per-cell virtual-call/boxing blowup is far larger (+ 50 ms slack)
    }
}
