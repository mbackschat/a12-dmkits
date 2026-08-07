package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * **The perf-coverage denominator: no evaluation entry point may exist without a timing guard.**
 *
 * Four of [DmInterpreter]'s eight document-evaluation entry points had no timing test whatsoever, and that was
 * invisible because the coverage question was never asked against a *list*. The gap is expensive: an entry
 * point nothing times is exactly where a quadratic hides (partial validation was O(N²) for its whole life,
 * unseen because no scenario called `validatePart`).
 *
 * So the entry points are **discovered by reflection**, not enumerated by hand: adding a public method that
 * takes a [Document] fails this test until it is either given a timing guard or explicitly recorded as not
 * needing one. A hand-written list would have to be remembered; a derived one cannot be forgotten.
 *
 * This guard is deliberately **not** `@Tag("perf")` — it is a cheap reflection check that must run in the
 * ordinary lane, so the coverage question is answered on every build rather than only when someone opts into
 * the perf tier.
 */
class InterpreterPerfCoverageTest {

    @Test
    fun everyEvaluationEntryPointHasATimingGuard() {
        val discovered = evaluationEntryPoints().map { it.name }.toSortedSet()

        assertEquals(
            COVERAGE.keys.toSortedSet(), discovered,
            "the set of DmInterpreter evaluation entry points changed. Every public method taking a Document " +
                "is timed by some perf guard; add the new one to COVERAGE with the guard that times it (or, if " +
                "it genuinely cannot be timed, record it here with the reason). Missing a guard is how " +
                "validatePart stayed quadratic unnoticed.",
        )
    }

    /**
     * **The published facade is a SECOND denominator, and it is enumerated here so it cannot go unnoticed.**
     *
     * `Interpreter` (the `DocumentSnapshot`-based library API external consumers actually call) evaluates
     * through the same engine, but its methods take a `DocumentSnapshot` rather than a [Document], so the
     * reflection above is blind to them. Discovering that immediately after writing IF254 is the finding's own
     * lesson arriving one surface later: the risk is never the entry point you remembered to check.
     *
     * Each is dispositioned rather than timed. The facade adds two things to an already-timed core call — an
     * O(1) snapshot unwrap (`DocumentSnapshot.document()` returns a stored field) and an O(1)-per-finding
     * pointer projection (`ModelAddressValidation.entityKind` is two set lookups) — so its realistic worst case
     * is a constant factor, not a complexity class. Timing it properly needs a public snapshot-construction
     * path at scale for BOTH engines, which is a new harness rather than one run; until that exists this test
     * keeps the surface visible and its reasoning attached.
     */
    @Test
    fun theSnapshotFacadeIsEnumeratedEvenThoughItIsNotSeparatelyTimed() {
        val facade = Class.forName("io.github.mbackschat.a12.dm.interpreter.Interpreter")
        val snapshot = Class.forName("io.github.mbackschat.a12.dm.interpreter.DocumentSnapshot")
        val discovered = facade.methods
            .filter { it.declaringClass == facade }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
            .filter { m -> m.parameterTypes.any { it == snapshot } }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            FACADE_DISPOSITION.keys.toSortedSet(), discovered,
            "the published Interpreter facade's evaluation surface changed. Each snapshot-taking method must " +
                "be dispositioned here: it delegates to a timed core call, or it needs its own guard. This is " +
                "the second denominator — DmInterpreter's is the reflection above.",
        )
    }

    /**
     * The map must keep pointing at something real. A renamed or deleted test would otherwise leave the
     * coverage claim standing while the guard it names no longer exists — an advertised invariant with no
     * enforcement locus.
     */
    @Test
    fun everyNamedGuardStillExists() {
        for ((entryPoint, guard) in COVERAGE) {
            val owner = runCatching { Class.forName(guard.testClass) }.getOrElse {
                throw AssertionError("$entryPoint names a perf guard whose class is gone: ${guard.testClass}")
            }
            val methods = owner.methods.map { it.name }.toSet()
            check(guard.testMethod in methods) {
                "$entryPoint names ${guard.testClass}.${guard.testMethod}, which no longer exists. " +
                    "Point it at the guard that times $entryPoint today."
            }
        }
    }

    /** Where one entry point's timing coverage lives. */
    private data class Guard(val testClass: String, val testMethod: String, val note: String)

    private companion object {

        /**
         * Every public [DmInterpreter] method that evaluates over a [Document]. Kotlin's `internal` members
         * compile to public bytecode under a mangled `name$module` form, and default-argument bridges are
         * likewise `$`-suffixed; both are implementation detail rather than API, so `$` filters them out.
         */
        fun evaluationEntryPoints(): List<Method> = DmInterpreter::class.java.methods
            .filter { it.declaringClass == DmInterpreter::class.java }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
            .filter { m -> m.parameterTypes.any { it == Document::class.java } }

        private const val PERF = "io.github.mbackschat.a12.dm.adapter.perf"
        private const val LAWS = "io.github.mbackschat.a12.dm.adapter.laws"

        val COVERAGE: Map<String, Guard> = mapOf(
            "validate" to Guard(
                "$PERF.PerfGateTest", "scalingIsSubQuadratic",
                "the sweep's own operation: InterpreterPerfEngine times validate for every VALIDATE scenario",
            ),
            "validateFull" to Guard(
                "$PERF.OuterCorrelationPerfTest", "equalityOuterCorrelationStaysSubQuadratic",
                "times validateFull directly; the cold-start gate drives the same path with no warmup",
            ),
            "validatePart" to Guard(
                "$LAWS.PartialSumOfProductsPerfTest", "completeConcreteEnumerationStaysSubQuadraticAndBeatsTheKernel",
                "the relevant set grows WITH the document under a concrete enumeration — where the O(N²) was",
            ),
            "compute" to Guard(
                "$PERF.PerfBenchTest", "gatesEveryInventoryScenario",
                "the sweep's other operation: B7 / compute-cleared / compute-errored / tiered-compute",
            ),
            "testRule" to Guard(
                "$PERF.EntryPointPerfTest", "testRuleBeatsTheKernelAndScales",
                "single stored rule over the whole document",
            ),
            "evalCandidate" to Guard(
                "$PERF.EntryPointPerfTest", "evalCandidateBeatsTheKernelAndScales",
                "single injected candidate rule — the model eval --condition shape",
            ),
            "computeField" to Guard(
                "$PERF.EntryPointPerfTest", "computeFieldBeatsTheKernelAndScales",
                "one computation's pruned subset",
            ),
            "applyComputations" to Guard(
                "$PERF.EntryPointPerfTest", "applyComputationsBeatsTheKernelAndScales",
                "compute plus the whole-document rebuild — the rebuild is the part nothing else times",
            ),
        )

        /** The published facade's evaluation surface, each with why it is not separately timed. */
        val FACADE_DISPOSITION: Map<String, String> = mapOf(
            "validateFull" to "delegates to DmInterpreter.validate; adds an O(1) unwrap + O(1)-per-finding projection",
            "validatePart" to "delegates to DmInterpreter.validatePart, timed by PartialSumOfProductsPerfTest",
            "compute" to "delegates to the retained compute run timed by the B7 inventory row",
            "applyComputations" to "defined as compute + ComputationReport.applyTo; both halves delegate",
        )
    }
}
