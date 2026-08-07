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
    }
}
