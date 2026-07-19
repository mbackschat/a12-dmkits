package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.InterpreterKernelFuzzTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/**
 * The CAPABILITY × STRATEGY EVIDENCE MATRIX (IF140, increment 1 of IG94). [CatalogParityTest] makes *operator*
 * coverage a derived, can't-rot guard; this makes the **strategy dimension** — does each capability have evidence
 * against the kernel's DYNAMIC (Groovy-dynamic) runtime AND its STATIC (java-static codegen) strategy — an
 * explicit, compile-checked accounting for the axes an operator-keyed guard can't see: formal families,
 * computation axes, model configuration, and the customization SPIs.
 *
 * The three rules (owner, 2026-07-19):
 *  - **Evidence names a test.** Each covered capability points at the `*DiffTest` that proves it — by [KClass],
 *    so a rename/delete **breaks the build**, never rots. Its static status is one of [Static.Tri] (a real
 *    static-JAVA tri-check exists) or [Static.DynamicOnly] (dynamic-locked; static evidence is a tracked gap).
 *  - **`DynamicOnly` is tracked, never silent.** A dynamic-only capability is NOT "covered" — it is reported
 *    with its gap reference so the missing static-JAVA half is visible, not assumed.
 *  - **Exclusions are explicit, reasoned, and NOT evidence.** A capability legitimately outside the
 *    static-JAVA frame (a JS platform edge, kernel-side model assembly) is [Coverage.Excluded] with a reason and
 *    is counted separately — an exclusion never inflates the covered count.
 *
 * Increment 1 is an **axis-level** guard over a curated capability list (the [CatalogParityTest] pattern: curated
 * but derived-checked). The full per-cell matrix, the CI/opt-in/JVM-only/JVM+JS coverage-tier dimension, and
 * generating the certificate summary from the matrix remain tracked IG94 follow-on (INTERPRETER-GAPS §2).
 */
class CapabilityEvidenceMatrixTest {

    private enum class Axis { OPERATOR, FORMAL, COMPUTATION, CONFIG, CUSTOMIZATION }

    /** The static-JAVA (codegen) evidence status of a capability that has dynamic evidence. */
    private sealed interface Static {
        /** A static-JAVA tri-check exists (interp == groovy-dynamic == java-static) — [test] carries it. */
        data class Tri(val test: KClass<*>) : Static

        /** Dynamic-locked only; the static-JAVA half is a tracked gap ([why] names it). NOT "covered". */
        data class DynamicOnly(val why: String) : Static
    }

    private sealed interface Coverage {
        /** [dynamic] proves the capability against the Groovy-dynamic runtime; [static] is its static-JAVA status. */
        data class Evidence(val dynamic: KClass<*>, val static: Static) : Coverage

        /** Outside the static-JAVA evidence frame, with a [why] — reasoned, and never counted as evidence. */
        data class Excluded(val why: String) : Coverage
    }

    private data class Capability(val axis: Axis, val id: String, val coverage: Coverage)

    // The fuzz net runs interp == groovy-dynamic == java-static over random conditions, so it is the operator
    // axis's static-JAVA evidence; the non-operator axes are not in the condition fuzz and need dedicated tests.
    private val fuzzTri = Static.Tri(InterpreterKernelFuzzTest::class)

    private val matrix: List<Capability> = listOf(
        // ── OPERATOR — derived by CatalogParityTest (handling + fuzz); tri-engine via the fuzz net ──────────────
        Capability(Axis.OPERATOR, "operators (110/110, derived)", Coverage.Evidence(CatalogParityTest::class, fuzzTri)),

        // ── FORMAL families — not in the condition fuzz, so each needs a dedicated differential ────────────────
        Capability(Axis.FORMAL, "length (min/max, UTF-16 units)", Coverage.Evidence(AbsLengthDiffTest::class,
            Static.DynamicOnly("formal families are not in the condition fuzz; no static-JAVA tri-check yet (IG94 static follow-on)"))),
        Capability(Axis.FORMAL, "pattern (declared + predefined)", Coverage.Evidence(PatternDiffTest::class,
            Static.DynamicOnly("dynamic-locked; static-JAVA tri-check pending (IG94); JS regex parity is IG90"))),
        Capability(Axis.FORMAL, "value bounds (min/max value)", Coverage.Evidence(FieldConstraintFormalDiffTest::class,
            Static.DynamicOnly("dynamic-locked; static-JAVA tri-check pending (IG94)"))),
        Capability(Axis.FORMAL, "scale / sign / precision", Coverage.Evidence(NumberSignFormalDiffTest::class,
            Static.DynamicOnly("dynamic-locked; static-JAVA tri-check pending (IG94)"))),
        Capability(Axis.FORMAL, "legal charset (BMP / DIN-91379)", Coverage.Evidence(SupportedCharactersDiffTest::class,
            Static.DynamicOnly("dynamic-locked; static-JAVA tri-check pending (IG94); combined-grapheme atomicity is IG88"))),
        Capability(Axis.FORMAL, "raw-type (noValueValidation)", Coverage.Evidence(NvvRawTypeDiffTest::class,
            Static.Tri(NvvRawTypeDiffTest::class))),

        // ── COMPUTATION axes ──────────────────────────────────────────────────────────────────────────────────
        Capability(Axis.COMPUTATION, "tiered / precondition-guarded", Coverage.Evidence(ComputationGuardedValidationDiffTest::class,
            Static.Tri(ComputationGuardedValidationDiffTest::class))),
        Capability(Axis.COMPUTATION, "cascade (dependency order)", Coverage.Evidence(ComputeCascadeDiffTest::class,
            Static.DynamicOnly("dynamic-locked; static-JAVA tri-check pending (IG94)"))),
        Capability(Axis.COMPUTATION, "multi-field / cross-root", Coverage.Evidence(MultiComputationFieldDiffTest::class,
            Static.Tri(MultiComputationFieldDiffTest::class))),
        Capability(Axis.COMPUTATION, "tolerance comparison", Coverage.Evidence(ToleranceDiffTest::class,
            Static.DynamicOnly("dynamic-locked; static-JAVA tri-check pending (IG94); toleranceRangeOp drop is IG96"))),

        // ── CONFIG (model configuration) ──────────────────────────────────────────────────────────────────────
        Capability(Axis.CONFIG, "timeZone (datetime arithmetic)", Coverage.Evidence(DstTimeZoneDiffTest::class,
            Static.DynamicOnly("dynamic-locked; static-JAVA tri-check pending (IG94); full tz-id/history domain is IG83"))),
        Capability(Axis.CONFIG, "locale (message language)", Coverage.Excluded(
            "locale reaches only message TEXT, which is license-excluded (IF3); the observable code/type/pointer are locale-independent")),

        // ── CUSTOMIZATION SPIs ────────────────────────────────────────────────────────────────────────────────
        Capability(Axis.CUSTOMIZATION, "CustomCondition SPI", Coverage.Evidence(CustomConditionDiffTest::class,
            Static.DynamicOnly("dynamic-locked (incl. partial); static-JAVA tri-check pending (IG94)"))),
        Capability(Axis.CUSTOMIZATION, "CustomFieldType SPI", Coverage.Evidence(CustomFieldTypeBoundsDiffTest::class,
            Static.DynamicOnly("dynamic-locked; static-JAVA tri-check pending (IG94); rejection-code identity is IG92"))),
        Capability(Axis.CUSTOMIZATION, "predefined types", Coverage.Evidence(PredefinedTypeValidityDiffTest::class,
            Static.DynamicOnly("dynamic-locked; static-JAVA tri-check pending (IG94)"))),

        // ── an API-safety cell IG94 must account for: message-only differentials can't prove nothing was skipped
        Capability(Axis.FORMAL, "unsupported-rule safety (validateFull)", Coverage.Evidence(ValidateFullDiffTest::class,
            Static.DynamicOnly("validateFull fails loud on unsupported (IF139); the coverage lock is the interpreter's ValidateFullUnsupportedSafetyTest (JVM+JS)"))),
    )

    private val evidence get() = matrix.mapNotNull { it.coverage as? Coverage.Evidence }
    private val exclusions get() = matrix.filter { it.coverage is Coverage.Excluded }

    /** Every capability id is unique within its axis — a duplicate means a stale/double classification. */
    @Test
    fun everyCapabilityIsClassifiedExactlyOnce() {
        val dupes = matrix.groupBy { it.axis to it.id }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty()) { "capabilities classified more than once:\n  " + dupes.joinToString("\n  ") }
    }

    /** Exclusions carry a non-blank reason. They are evidence-disjoint by construction (a distinct sealed case),
     *  so an exclusion can never inflate the covered count — [coverageMeter] counts evidence and exclusions apart. */
    @Test
    fun exclusionsAreReasonedAndNotEvidence() {
        val blank = exclusions.filter { (it.coverage as Coverage.Excluded).why.isBlank() }
        assertTrue(blank.isEmpty()) { "excluded capabilities without a reason:\n  " + blank.joinToString("\n  ") { it.id } }
    }

    /** A dynamic-only capability is tracked with its gap reference, never silently treated as fully covered. */
    @Test
    fun dynamicOnlyEvidenceIsTrackedNotSilent() {
        val untracked = evidence.mapNotNull { it.static as? Static.DynamicOnly }.filter { it.why.isBlank() }
        assertTrue(untracked.isEmpty()) {
            "dynamic-only capabilities must name the tracked static-JAVA gap (never a blank reason):\n  " +
                untracked.joinToString("\n  ") { it.why }
        }
    }

    /** The coverage meter — derived, not hand-counted: tri-engine vs dynamic-only vs reasoned-exclusion. */
    @Test
    fun coverageMeter() {
        val tri = evidence.count { it.static is Static.Tri }
        val dynOnly = evidence.count { it.static is Static.DynamicOnly }
        println("[capability-matrix] ${matrix.size} capabilities: $tri tri-engine (dynamic+static), " +
            "$dynOnly dynamic-only (static-JAVA pending, IG94), ${exclusions.size} reasoned-exclusion")
        Axis.entries.forEach { ax ->
            val inAxis = matrix.filter { it.axis == ax }
            val t = inAxis.count { (it.coverage as? Coverage.Evidence)?.static is Static.Tri }
            println("[capability-matrix]   ${ax.name.lowercase()}: ${inAxis.size} caps, $t tri-engine")
        }
        // increment-1 invariant: nothing is silently uncovered — every capability is evidence or reasoned-exclusion.
        assertTrue(evidence.size + exclusions.size == matrix.size) {
            "every capability must be Evidence or Excluded (the partition invariant)"
        }
    }
}
