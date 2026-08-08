package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * **The perf-coverage denominator: no evaluation entry point may exist without a NAMED timing guard.**
 *
 * The word "named" is load-bearing and the promise stops there — see [everyNamedGuardStillExists] for what is
 * and is not established. This class derives the denominator; it does not verify that each named test calls the
 * entry point it claims.
 *
 * Four of [DmInterpreter]'s eight document-evaluation entry points had no timing test whatsoever, and that was
 * invisible because the coverage question was never asked against a *list*. The gap is expensive: an entry
 * point nothing times is exactly where a quadratic hides (partial validation was quadratic **under a relevant
 * set that grows with the document**, unseen for its whole life because no scenario called `validatePart`;
 * with a single wildcard entity the same code stayed linear, so the unqualified claim overstated it).
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
        val discovered = evaluationEntryPoints()

        assertEquals(
            COVERAGE.keys.toSortedSet(), discovered.map(::signature).toSortedSet(),
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
     * Each delegates to a timed core call, so none carries its own timing guard. **That disposition was first
     * written as "adds an O(1)-per-finding projection, so the worst case is a constant factor", and it was
     * wrong** — reasoned from `ModelAddressValidation.entityKind` (two set lookups, correct in itself) while
     * missing the pointer-list copy beside it, which the engine had deliberately shared across findings. The
     * façade allocated quadratically and exhausted the heap at 8,000 duplicate rows; `FacadeProjectionSharingTest`
     * now pins the sharing directly. The lesson is why this list exists: a disposition reasoned from one line of
     * a function is not a measurement of the function.
     */
    @Test
    fun theSnapshotFacadeIsEnumeratedEvenThoughItIsNotSeparatelyTimed() {
        val facade = Class.forName("io.github.mbackschat.a12.dm.interpreter.Interpreter")
        val snapshot = Class.forName("io.github.mbackschat.a12.dm.interpreter.DocumentSnapshot")
        val discovered = facade.methods
            .filter { it.declaringClass == facade }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
            .filter { m -> m.parameterTypes.any { it == snapshot } }

        assertEquals(
            FACADE_DISPOSITION.keys.toSortedSet(), discovered.map(::signature).toSortedSet(),
            "the published Interpreter facade's evaluation surface changed. Each snapshot-taking method must " +
                "be dispositioned here: it delegates to a timed core call, or it needs its own guard. This is " +
                "the second denominator — DmInterpreter's is the reflection above.",
        )
    }

    /**
     * **Both denominators are keyed by SIGNATURE, not by method name.** Keyed by name, an overload is invisible
     * in them: a second `validatePart(Document, …)` with independent behaviour and no timing test would leave
     * the discovered set unchanged and pass. That is not hypothetical — `Interpreter.compute` already had two
     * public signatures (with and without a locale) collapsed under one disposition, which nobody had noticed
     * because the question was asked of a name. Signatures make each one answer for itself, which is the same
     * "derive the denominator, never enumerate it" the class is built on, one level further down.
     */
    private fun signature(method: Method): String =
        method.parameterTypes.joinToString(",", "${method.name}(", ")") { it.simpleName }

    /**
     * The map must keep pointing at something real. A renamed or deleted test would otherwise leave the
     * coverage claim standing while the guard it names no longer exists — an advertised invariant with no
     * enforcement locus.
     *
     * **What this does NOT establish: that the named test exercises the entry point.** Pointing `validatePart`
     * at any `@Test` method anywhere would pass. Closing that needs the claim to be made *at* the timing guard
     * rather than in a map beside it (an annotation on the test method, derived instead of authored); it is
     * recorded as IG111 in `docs/INTERPRETER-GAPS.md` rather than left as an implicit weakness. Until then the claim
     * this class enforces is exactly "every entry point is NAMED here and the name resolves to a live test",
     * which is what the assertions below check and all the wording in this file should promise.
     */
    @Test
    fun everyNamedGuardStillExists() {
        for ((entryPoint, guard) in COVERAGE) {
            val owner = runCatching { Class.forName(guard.testClass) }.getOrElse {
                throw AssertionError("$entryPoint names a perf guard whose class is gone: ${guard.testClass}")
            }
            val named = owner.methods.filter { it.name == guard.testMethod }
            check(named.isNotEmpty()) {
                "$entryPoint names ${guard.testClass}.${guard.testMethod}, which no longer exists. " +
                    "Point it at the guard that times $entryPoint today."
            }
            // Existence alone would accept any method at all — a helper, or something inherited from Object.
            // This does NOT establish that the named test exercises the entry point; nothing cheap does, and
            // claiming otherwise would be the "advertise what you cannot point at" defect one level up. It
            // rules out the mis-pointing that costs nothing to rule out.
            check(named.any { it.isAnnotationPresent(Test::class.java) }) {
                "$entryPoint names ${guard.testClass}.${guard.testMethod}, which is not a @Test method. A " +
                    "coverage claim must point at something that actually runs."
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
            "validate(Document,String)" to Guard(
                "$PERF.PerfGateTest", "scalingIsSubQuadratic",
                "the sweep's own operation: InterpreterPerfEngine times validate for every VALIDATE scenario",
            ),
            "validateFull(Document,String)" to Guard(
                "$PERF.OuterCorrelationPerfTest", "equalityOuterCorrelationStaysSubQuadratic",
                "times validateFull directly; the cold-start gate drives the same path with no warmup",
            ),
            "validatePart(Document,Set,String)" to Guard(
                "$LAWS.PartialSumOfProductsPerfTest", "completeConcreteEnumerationStaysSubQuadraticAndBeatsTheKernel",
                "the relevant set grows WITH the document under a concrete enumeration — where the O(N²) was",
            ),
            "compute(Document,String)" to Guard(
                "$PERF.PerfBenchTest", "gatesEveryInventoryScenario",
                "the sweep's other operation: B7 / compute-cleared / compute-errored / tiered-compute",
            ),
            "testRule(String,Document,String)" to Guard(
                "$PERF.EntryPointPerfTest", "testRuleBeatsTheKernelAndScales",
                "single stored rule over the whole document",
            ),
            "evalCandidate(RuleDef,Document,String)" to Guard(
                "$PERF.EntryPointPerfTest", "evalCandidateBeatsTheKernelAndScales",
                "single injected candidate rule — the model eval --condition shape",
            ),
            "computeField(String,Document,String)" to Guard(
                "$PERF.EntryPointPerfTest", "computeFieldBeatsTheKernelAndScales",
                "one computation's pruned subset",
            ),
            "applyComputations(Document,String)" to Guard(
                "$PERF.EntryPointPerfTest", "applyComputationsBeatsTheKernelAndScales",
                "compute plus the whole-document rebuild — the rebuild is the part nothing else times",
            ),
        )

        /**
         * The published facade's evaluation surface, each with why it is not separately timed.
         *
         * Every name here is a **locale-overload pair**: the short form supplies the default locale and calls
         * the long one. Keying by signature is what made that visible — under name keys the four pairs read as
         * four entries, and `compute` had been carrying two public signatures under one disposition since it
         * shipped.
         */
        val FACADE_DISPOSITION: Map<String, String> = mapOf(
            "validateFull(DocumentSnapshot)" to "default-locale overload of validateFull(DocumentSnapshot,String)",
            "validateFull(DocumentSnapshot,String)" to
                "delegates to DmInterpreter.validate; adds an O(1) unwrap + a per-finding projection that " +
                "publishes the engine's shared pointer list without copying it (FacadeProjectionSharingTest)",
            "validatePart(DocumentSnapshot,List)" to "default-locale overload of validatePart(DocumentSnapshot,List,String)",
            "validatePart(DocumentSnapshot,List,String)" to
                "delegates to DmInterpreter.validatePart, timed by PartialSumOfProductsPerfTest",
            "compute(DocumentSnapshot)" to "default-locale overload of compute(DocumentSnapshot,String)",
            "compute(DocumentSnapshot,String)" to "delegates to the retained compute run timed by the B7 inventory row",
            "applyComputations(DocumentSnapshot)" to "default-locale overload of applyComputations(DocumentSnapshot,String)",
            "applyComputations(DocumentSnapshot,String)" to
                "defined as compute + ComputationReport.applyTo; both halves delegate",
        )
    }
}
