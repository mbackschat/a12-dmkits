package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * **A real measurement may only be taken from a lane that can measure.**
 *
 * `:adapter:test` forks 2-4 JVMs in parallel, which is the configuration a timed task must never run in: a
 * neighbouring fork's scheduler preemption lands in the observation, and it once manufactured a 10.24x growth
 * failure in a class nothing had touched. `PerfTestClassificationTest` enforces the two *syntactic* ends of the
 * convention (a `*PerfTest` name implies a lane tag, and a lane tag implies a measuring name), and a cold review
 * pointed out what that can never see: a clock-reading assertion written under a neutral name with no tag at all.
 *
 * This is the runtime half. The two seams that build a **bar** — [PerfHarness.assertSubQuadraticLane] and
 * [KernelAdvantage.assertBeatsKernelAndScales] — ask here first, so a lane that asserts on a measurement must be
 * in an opted-in tier whatever its class is called. The complementary half — a test that reads
 * `System.nanoTime()` directly and never reaches the harness — is a source census (`RawClockCensusTest`), because
 * no runtime seam can see a call that does not pass through it. Neither mechanism subsumes the other.
 *
 * **Gated at the bar, not at the measurement** (narrowed 2026-08-13, after the broad form was run against the
 * whole suite). Gating [PerfHarness]'s four measuring entry points was tried first and refused four legitimate
 * tests: `PerfRuntimeBindingsTest` and `PerfPreflightTest` *invoke* a timing helper to drive the engines and then
 * assert on **semantics** — a rejection reaching all three engines, a callback failing after the sample cap — not
 * on any duration. Taking a measurement is not the defect; asserting a bar on it outside an isolated lane is.
 * Exempting those by name would have started exactly the allowlist decay this design is trying to avoid.
 *
 * **The residue that leaves, stated:** a test that calls `PerfHarness.timeInterpreter` and then hand-rolls
 * `assertTrue(ms < 50)` builds a bar without either seam, and reads no clock of its own, so neither mechanism
 * sees it. What remains against that is the convention `PerfTestClassificationTest` enforces at the name/tag ends.
 *
 * **The exemption rule is derived, not a list**, so it cannot rot as classes are added:
 *
 * - a **performance lane tag** (`perf` routine tier, `perf-bench` checkpoint tier) — the lane is isolated, or is
 *   run deliberately by hand;
 * - **`@EnabledIfSystemProperty`** — a diagnostic or profiler that returns on its first line unless someone asks
 *   for it by name, so it cannot pollute an ordinary build;
 * - the **harness itself** and its worker infrastructure, which measure by definition;
 * - one **explicit** entry, carrying its reason.
 */
internal object PerfLaneGate {

    /**
     * The single reasoned exception, kept here rather than in a comment so it is impossible to add a second one
     * silently.
     *
     * `PerfGrowthEstimatorTest.currentThreadCpuExcludesBlockedWallTime` sleeps 100 ms and asserts that the CPU
     * clock read *less* than a quarter of that — it protects the contract every growth floor rests on
     * ([PerfHarness.measureCurrentThreadCpu] excludes blocked time), so it must be answered on every push rather
     * than inside a 4-minute gate. Its exposure to contention is **directional**: load only adds wall time and
     * leaves CPU-during-sleep near zero, so both of its assertions get *easier* under load. That reasoning is
     * specific to its shape and is why the exemption is one class, not a category.
     */
    private val ALLOWED = mapOf(
        "io.github.mbackschat.a12.dm.adapter.perf.PerfGrowthEstimatorTest" to
            "protects the CPU-clock contract itself and is contention-SAFE: load only adds wall time, so both " +
                "assertions move further into passing",
    )

    /** Infrastructure that measures by definition — the harness, its anchors, and the out-of-process workers. */
    private val INFRASTRUCTURE = setOf(
        "io.github.mbackschat.a12.dm.adapter.perf.PerfHarness",
        "io.github.mbackschat.a12.dm.adapter.perf.PerfAnchors",
        "io.github.mbackschat.a12.dm.adapter.perf.PerfObservations",
        "io.github.mbackschat.a12.dm.adapter.perf.KernelAdvantage",
        "io.github.mbackschat.a12.dm.adapter.perf.PerfLaneGate",
        "io.github.mbackschat.a12.dm.adapter.perf.NativePerformanceRunner",
    )

    /**
     * The class that OWNS a frame: a Kotlin lambda compiles to a synthetic or anonymous inner class, so those walk
     * out to their enclosing declaration, while a genuine nested class answers for itself.
     *
     * Written first as `name.substringBefore('$')`, which is wrong in the lenient direction and was caught by this
     * class's own fixtures: it strips real nesting too, so every nested helper resolved to the enclosing test and
     * inherited its lane. A tagged outer class would then have laundered an untagged inner one.
     */
    private fun owningClass(type: Class<*>): Class<*> {
        var owner = type
        while (owner.isSynthetic || owner.isAnonymousClass || owner.isLocalClass) {
            owner = owner.enclosingClass ?: return owner
        }
        return owner
    }

    /** Why [type] may not measure, or `null` when it may. Public so the refusal has an executable positive control. */
    fun refusalFor(type: Class<*>): String? {
        val owner = owningClass(type)
        val name = owner.name
        if (name in INFRASTRUCTURE || name in ALLOWED) {
            return null
        }
        // Self FIRST, then out through the enclosing classes. A test class routinely does its measuring from a
        // nested helper — `EntryPointPerfTest.Rig` owns the whole read/assert cycle for its four cases — and that
        // helper is only reachable from the tagged class that encloses it, so it is in that class's lane. Checking
        // the nested class alone refused all four of them (measured 2026-08-13: zero observations recorded). The
        // self-first order is what keeps the converse working: a tagged nested class inside an untagged file is
        // admitted on its own tag, and an untagged one there is still refused.
        var scope: Class<*>? = owner
        while (scope != null) {
            if (scope.name in INFRASTRUCTURE || scope.name in ALLOWED) {
                return null
            }
            if (scope.getAnnotationsByType(Tag::class.java).any { it.value in LANE_TAGS }) {
                return null
            }
            if (scope.isAnnotationPresent(EnabledIfSystemProperty::class.java)) {
                return null
            }
            scope = scope.enclosingClass
        }
        return "$name takes a real measurement but is in no performance lane. :adapter:test forks 2-4 JVMs in " +
            "parallel, so a timing observation there measures contention rather than the interpreter — that is " +
            "how a false 10.24x growth failure was once manufactured in an untouched class. Give the class " +
            "@Tag(\"perf\") (routine gate) or @Tag(\"perf-bench\") (checkpoint tier), or gate it behind " +
            "@EnabledIfSystemProperty if it is a diagnostic nobody should run by accident."
    }

    /**
     * Refuse if the class that called into the harness may not measure.
     *
     * The caller is found by walking out of this package's own infrastructure — the first frame that is neither
     * the gate nor the harness owns the measurement, whatever the call depth or the lambda nesting.
     */
    fun requireMeasuringLane() {
        val caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { frames ->
            frames.map { owningClass(it.declaringClass) }
                .filter { it.name !in INFRASTRUCTURE }
                .findFirst().orElse(null)
        } ?: return
        refusalFor(caller)?.let { throw AssertionError(it) }
    }

    val LANE_TAGS = setOf("perf", "perf-bench")
}
