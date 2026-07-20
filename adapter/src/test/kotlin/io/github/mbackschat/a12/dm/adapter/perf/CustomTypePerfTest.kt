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
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Perf guard for the declarative custom-field-type path (`--predefined-types` / `predefinedTypes` registry, G30 /
 * IF26). Validating a `CustomFieldType` cell runs `PredefinedType.accepts(value)` **per valued cell** on the
 * formal-error HOT path — it MUST stay a monomorphic, JIT-inlined call on the concrete `PredefinedType`, NOT a
 * per-cell virtual/megamorphic dispatch or a per-cell allocation. This is the standing
 * [INTERPRETER-PERFORMANCE.md] "the hot path stays a monomorphic concrete call" rule made into a regression
 * gate: IF44's code-based field-type SPI is deliberately additive; a naive "route every field type through one
 * `fun interface`" refactor would deoptimize the declarative path — and this test trips.
 *
 * Two guards (the [CharsetPerfTest] sibling pattern — pure engine, no kernel, JVM-only; the ratio/growth cancels
 * machine speed):
 *  1. **Within a factor of plain-string-with-the-same-pattern** — a custom-typed cell and a plain STRING cell
 *     carrying the identical regex do the same regex match; the custom path adds only the registry lookup +
 *     `accepts` dispatch. So it must stay within a small factor. This catches the GROSS deopts a careless SPI
 *     introduction would cause — a **per-cell allocation** (e.g. boxing a result object instead of returning a
 *     primitive), **megamorphic dispatch**, or a per-cell extra pass — not a single inlined-away virtual call
 *     (which is within timing noise and not a real perf concern).
 *  2. **Sub-quadratic in row count** — validating 4× the rows must stay ~linear (≈4×), never ≈16×; catches an
 *     O(N²) regression in the per-cell custom-type machinery.
 */
class CustomTypePerfTest {

    private val s = "/C/S"
    private val customDef = ModelDef(listOf(
        FieldDef(s, Kind.STRING, customType = CustomFieldTypeDeclaration("Iban")),
    ),
        emptyList(), declaredReps = mapOf("/C" to MAX))
    private val stringDef = ModelDef(listOf(FieldDef(s, Kind.STRING, pattern = IBAN)),
        emptyList(), declaredReps = mapOf("/C" to MAX))
    private val registry = mapOf("Iban" to PredefinedType(minLength = 15, maxLength = 34, pattern = IBAN))

    /** A document of [n] custom-typed cells, all carrying a valid IBAN (so `accepts` runs the full regex per cell). */
    private fun doc(n: Int): Document {
        val d = customDef.newDocument()   // kinds are STRING for both defs → one doc serves both
        for (i in 1..n) d.field(s, "DE89370400440532013000", 1, i, 1)
        return d
    }

    @Test fun customTypeValidationIsWithinAFactorOfPlainString() {
        val d = doc(N)
        // Correctness sanity — the timed path is NOT a no-op: a bad value fires customFieldTypeInvalid, and the
        // registered type is actually consulted (nothing reported unsupported).
        val bad = customDef.newDocument().apply { field(s, "NOT-AN-IBAN", 1, 1, 1) }
        val outcome = DmInterpreter(customDef, predefinedTypes = registry).validate(bad)
        assertEquals(1, outcome.messages.size, "the accepts() path must run (a bad IBAN fires once): ${outcome.messages}")
        assertTrue(outcome.unsupported.isEmpty(), "the registered type must be consulted, not reported unsupported: ${outcome.unsupported}")

        fun runCustom() = DmInterpreter(customDef, predefinedTypes = registry).validate(d)
        fun runString() = DmInterpreter(stringDef).validate(d)
        repeat(WARM) { runCustom(); runString() }
        val custom = measureTime { repeat(REPEATS) { runCustom() } }
        val string = measureTime { repeat(REPEATS) { runString() } }
        assertTrue(custom < string * FACTOR + 50.milliseconds,
            "custom-type validation ($custom) must stay within ${FACTOR}× plain-string-with-the-same-pattern ($string) " +
                "— a per-cell allocation / megamorphic dispatch on the field-type path regressed (see the monomorphic-hot-path rule)")
    }

    @Test fun customTypeValidationStaysSubQuadratic() {
        val docs = mapOf(N_SMALL to doc(N_SMALL), N_SMALL * 4 to doc(N_SMALL * 4))
        fun run(d: Document) = DmInterpreter(customDef, predefinedTypes = registry).validate(d)
        repeat(WARM) { docs.values.forEach { run(it) } }
        // IF137 paired estimator: interleaved N/4N, median of within-pair ratios (via the shared PerfHarness helper).
        val g = PerfHarness.pairedGrowth(N_SMALL, N_SMALL * 4) { n ->
            measureTime { run(docs.getValue(n)) }.inWholeMicroseconds / 1000.0
        }
        assertTrue(g.ratio < SUB_QUADRATIC_MAX,
            "custom-type validation growth for 4× rows was ${"%.2f".format(g.ratio)}× (linear≈4, quadratic≈16) — an O(N²) regression")
    }

    private companion object {
        const val IBAN = "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}"
        const val N = 50_000
        const val N_SMALL = 10_000
        const val MAX = 200_000          // declared repeatability ceiling — above the largest doc, so no over-repetition noise
        const val WARM = 3
        const val REPEATS = 5
        const val FACTOR = 6             // generous; a per-cell virtual-call/boxing blowup is far larger (+ 50 ms slack)
        const val SUB_QUADRATIC_MAX = 8.0 // linear≈4; trips well before quadratic≈16
    }
}
