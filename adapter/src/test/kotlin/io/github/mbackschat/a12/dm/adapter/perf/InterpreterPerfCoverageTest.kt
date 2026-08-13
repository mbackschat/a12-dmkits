package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * **The perf-coverage denominator: no evaluation entry point may exist without a timing guard that owns its
 * coverage claim.**
 *
 * [TimesInterpreterEntryPoint] sits on the timing test itself; this class discovers those annotations through
 * JUnit and resolves their typed signatures against [DmInterpreter]. There is no separate
 * class/method pointer that can be aimed at an unrelated test or survive a test rename.
 *
 * Four of [DmInterpreter]'s eight document-evaluation entry points had no timing test whatsoever, and that was
 * invisible because the coverage question was never asked against a *list*. The gap is expensive: an entry
 * point nothing times is exactly where a quadratic hides (partial validation was quadratic **under a relevant
 * set that grows with the document**, unseen for its whole life because no scenario called `validatePart`;
 * with a single wildcard entity the same code stayed linear, so the unqualified claim overstated it).
 *
 * So the entry points are **discovered by reflection**, not enumerated by hand: adding a public method that
 * takes a [Document] fails this test until a live `@Test` method in a performance lane names it with
 * [TimesInterpreterEntryPoint]. A hand-written list would have to be remembered; two derived sets cannot be
 * forgotten or drift apart.
 *
 * **The bound: a lane tag is not executable coverage** (stated after a review raised it, 2026-08-13). This check
 * proves a claim is owned by a live `@Test` that a performance lane *selects*; it does not prove that lane ever
 * runs it. JUnit discovery sees a `@EnabledIfSystemProperty` test, so a declaration parked on one would satisfy
 * the denominator while measuring nothing. No declaration sits on such a test today — the carriers are
 * `PerfGateTest`, `EntryPointPerfTest`, `OuterCorrelationPerfTest`, `PartialSumOfProductsPerfTest` and
 * `PerfBenchTest`, none of them property-gated — and the release gate now refuses a run that skipped anything,
 * which closes the same hole one layer out for the routine tier.
 *
 * This guard is deliberately **not** `@Tag("perf")` — it is a cheap reflection check that must run in the
 * ordinary lane, so the coverage question is answered on every build rather than only when someone opts into
 * the perf tier.
 */
class InterpreterPerfCoverageTest {

    @Test
    fun everyEvaluationEntryPointHasATimingGuard() {
        val discovered = evaluationEntryPoints()
        val guarded = timingGuards()
        val guardOwners = guarded.sortedBy { signature(it.entryPoint) }.joinToString(", ") {
            "${signature(it.entryPoint)} <- ${it.testMethod.declaringClass.simpleName}.${it.testMethod.name}"
        }

        assertEquals(
            discovered.map(::signature).toSortedSet(), guarded.map { signature(it.entryPoint) }.toSortedSet(),
            "the set of DmInterpreter evaluation entry points changed. Every public method taking a Document " +
                "must be named by @TimesInterpreterEntryPoint on the live @Test method that times it, in a " +
                "performance lane (@Tag perf or perf-bench). " +
                "Missing a guard is how validatePart stayed quadratic unnoticed. Discovered claims: " +
                guardOwners,
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

    private data class TimingGuard(val testMethod: Method, val entryPoint: Method)

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

        private const val ADAPTER_PACKAGE = "io.github.mbackschat.a12.dm.adapter"

        /**
         * The performance lane tags — `perf` is the routine gate's tier, `perf-bench` the checkpoint tier. Both
         * are accepted here because both denote a lane that actually times: `PerfBenchTest` owns the `compute`
         * declaration and is checkpoint-tier, so requiring `perf` alone would reject a real coverage claim (it did,
         * the moment the tiers were split — 2026-08-13). What must stay rejected is an *untagged* claim, which no
         * performance lane selects. The pair is closed and `PerfTestClassificationTest` keeps it that way.
         */
        private val PERFORMANCE_LANE_TAGS = setOf("perf", "perf-bench")

        /**
         * Discover the coverage declarations from a JUnit plan as executable test methods. An
         * annotation on a helper is rejected, as is one in an untagged class: a declaration is a timing guard only
         * when the ordinary JUnit engine sees a `@Test` and a performance lane selects it.
         */
        fun timingGuards(): List<TimingGuard> {
            val request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectPackage(ADAPTER_PACKAGE))
                .build()
            val plan = LauncherFactory.create().discover(request)
            val testMethods = plan.roots.asSequence()
                .flatMap { plan.getDescendants(it).asSequence() }
                .mapNotNull { it.source.orElse(null) as? MethodSource }
                .map { it.javaMethod }
                .distinct()
                .toList()

            return testMethods.mapNotNull { testMethod ->
                val owner = testMethod.declaringClass
                val claim = testMethod.getAnnotation(TimesInterpreterEntryPoint::class.java)
                    ?: return@mapNotNull null
                check(testMethod.isAnnotationPresent(Test::class.java)) {
                    "${owner.name}.${testMethod.name} claims interpreter timing coverage but is not a @Test"
                }
                val lanes = (owner.getAnnotationsByType(Tag::class.java).asSequence() +
                    testMethod.getAnnotationsByType(Tag::class.java).asSequence()).map { it.value }.toSet()
                check(lanes.any { it in PERFORMANCE_LANE_TAGS }) {
                    "${owner.name}.${testMethod.name} claims interpreter timing coverage but carries none of " +
                        "$PERFORMANCE_LANE_TAGS, so no performance lane selects it — an untagged claim would be " +
                        "satisfied by a test that never times anything"
                }
                val entryPoint = runCatching {
                    DmInterpreter::class.java.getMethod(
                        claim.method,
                        *claim.parameterTypes.map { it.java }.toTypedArray(),
                    )
                }.getOrElse {
                    throw AssertionError(
                        "${owner.name}.${testMethod.name} names no live DmInterpreter method: " +
                            "${claim.method}(${claim.parameterTypes.joinToString { it.simpleName ?: "?" }})",
                        it,
                    )
                }
                TimingGuard(testMethod, entryPoint)
            }
        }

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
