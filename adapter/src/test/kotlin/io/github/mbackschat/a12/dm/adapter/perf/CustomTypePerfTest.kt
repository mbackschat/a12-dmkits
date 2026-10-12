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
 * Admission-cost guards for declarative custom field types (`predefinedTypes`, IF26).
 *
 * Each valued cell consults formal admission; identical field values share its call-local result.
 * This repeated-IBAN fixture therefore measures per-cell cache consultations and one uncached validator
 * admission per call. It compares that complete path with a plain STRING carrying the same pattern,
 * then checks sub-quadratic growth in row count using current-thread CPU time. It does not isolate
 * validator dispatch cost or promise to detect an individual allocation.
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
     * A document of [n] custom-typed cells sharing one valid IBAN and one cached admission per call.
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
                "— declarative custom admission exceeded the relative cost allowance")
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
            identityAt = { n -> PerfHarness.fingerprintedLabel("custom-type-iban", "$IBAN|customDef|rows=$n") },
            batch = BATCH,
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
        /**
         * Document-level warms before the paired estimator runs. Raised 3 -> 10 on 2026-08-14: after batching, this
         * lane's median sits at ~18 ms — clear of the sub-10 ms band where the estate's noise lives — yet its
         * three-run spread stayed at 1.68x on `[28.73, 18.36, 17.06]`. That shape is not magnitude noise but the
         * first-run-after-rebuild effect: one high reading followed by two consistent ones. Warming the engine
         * further is the corrective for that, where a bigger batch is the corrective for the other.
         */
        const val WARM = 10
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
