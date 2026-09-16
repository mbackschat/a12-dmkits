package io.github.mbackschat.a12.dm.adapter.perf

import com.sun.management.ThreadMXBean
import io.github.mbackschat.a12.dm.consistency.ConsistencySnapshot
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.snapshotOf
import io.github.mbackschat.a12.dm.consistency.check as runChecker
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.util.Locale
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5's resources boundary, RETAINED-MEMORY half — the observation the boundary count hid until 2026-09-17.
 *
 * **Why this lane exists at all.** `docs/PORTABLE-CONSISTENCY-PROPOSAL.md` §8
 * asks this boundary for four things — "retained memory, release behavior, supported peak observations and
 * installed/downloaded production closure size" — and only the last was ever measured, as the packed tarball's
 * 634 443 bytes. The stage read DONE because the *boundary* was counted rather than its required observations,
 * and H5's blocking rule turns on the observation, not the count. See
 * `docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#stage-8`.
 *
 * **The quantities are the estate's, not new ones.** `:interpreter`'s ratified footprint estate reports a pair
 * — post-GC used bytes with the owner held LIVE, and thread-allocated bytes during the operation — behind a GC
 * viability gate and an owner-release check (`interpreter/src/commonTest/.../footprint/FootprintWorker.kt`).
 * This lane reports the same pair for a `ConsistencySnapshot` so the two are comparable; inventing a peak
 * sampler here would produce a third quantity nothing could be compared against, and §8's "supported peak
 * observations" is qualified by §8's own "where the host supports measurement".
 *
 * **It asserts the GROWTH RATIO and no byte count.** A budget taken from this lane's own figures is what H5's
 * ordering rule forbids, and `PerfBounds.SUB_QUADRATIC_MAX` escapes that because it was ratified as a mechanism
 * before `:consistency` existed — so applying it is a scope transfer onto a new carrier, which needs a
 * measurement here and has one (3.97x retained, 3.98x allocated, three runs, 2026-09-17). An absolute byte
 * ceiling is declined for the reason the delivery ceiling is honest and this one would not be: a tarball's
 * length is machine-independent and a post-GC heap reading is not. The ratified `>=5 forks for
 * footprint` sampling default is deliberately NOT met — this takes bracketed samples inside one JVM, as the
 * four sibling checker lanes did before their own dispositions — and under IF137 that default binds a number
 * only at its promotion, so the shortfall is disclosed rather than hidden.
 *
 * **Three gates, and each one voids the measurement rather than weakening it.** Explicit GC must be available
 * AND observably collect, or a post-GC reading is a reading of whatever happened to be uncollected; the
 * snapshot must be RELEASABLE once dropped, which is §8's "release behavior" and is the only thing here that
 * could reveal a leak; and the model must draw no rejecting finding, or the retained bytes belong to a
 * rejection rather than to an analysis.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.memory", matches = "true")
class CheckerRetainedMemoryPerfTest {

    @Test
    fun whatAHeldAnalysisRetainsAndWhatOneCheckAllocates() {
        JdkIdentity.requireSupported()
        val programs = requiredProperty("perf.checker.memory.n").toInt()
        val samples = requiredProperty("perf.checker.memory.samples").toInt()
        require(programs > 0) { "perf.checker.memory.n must be positive" }
        require(samples % 2 == 1) { "perf.checker.memory.samples must be odd so the median is an observation" }
        requireExplicitGcIsViable()

        val small = admitted(programs)
        val large = admitted(programs * 4)

        // The two BARS, and each is the growth ratio rather than any byte count. `SUB_QUADRATIC_MAX` was
        // ratified as a mechanism long before `:consistency` existed, so applying it here is a scope transfer
        // that needs a measurement on the new carrier and has one. An absolute byte ceiling is declined: a
        // post-GC heap reading moves with the JDK, the collector and the heap size, where a packed tarball's
        // length does not, so only the ratio survives a change of machine. What it catches is the failure class
        // no correctness test can see — a quadratic-memory checker returns entirely right answers and then
        // exhausts a heap, exactly as the cubic TIME term did before H5-b found it.
        //
        // Deliberately NOT `assertSubQuadraticLane`, which additionally anchors both endpoints as ABSOLUTE
        // observations through `PerfAnchors`: that record is milliseconds, and a byte count written into it
        // would be compared against a duration budget.
        //
        // `GATED` explicitly, overriding the growth floor's ambient `forGrowthFloor()` — which returns FLAGGED
        // whenever `CI` is set, because a self-referential TIMING floor is a property of the machine as much as
        // of the code. A byte ratio is not: a contended slice adds wall time and allocates nothing, so the
        // reason for flagging does not exist here, and the default would disarm this bar on every hosted run.
        //
        // The estimator is passed as a CLOSURE that re-samples, never as a precomputed median. That is the whole
        // point of the helper's acceptance rule: a breach must survive the median of independent re-measurements,
        // and handing it one constant three times makes the retry vacuous while reading exactly like a control.
        // Caught here on 2026-09-17 by the floor-lowering control itself, which printed three byte-identical
        // "independent" estimates.
        val retained = PerfHarness.assertGrowthBelowCatastropheFloor(
            "a held snapshot's retained bytes, $programs->${programs * 4} programs",
            culprit = "the fact base retaining per-declaration state it could derive",
            unit = " bytes",
            enforcement = TimingEnforcement.GATED,
        ) { growthOf(samples, small, large, ::retainedBytesOfOneSnapshot) }
        val allocated = PerfHarness.assertGrowthBelowCatastropheFloor(
            "one check's allocated bytes, $programs->${programs * 4} programs",
            culprit = "a family materializing an intermediate per declaration pair",
            unit = " bytes",
            enforcement = TimingEnforcement.GATED,
        ) { growthOf(samples, small, large) { allocatedBytesOfOneCheck(it.text) } }

        PerfHarness.progress(
            "[checker-memory] programs=$programs->${programs * 4} samples=$samples " +
                "model-text=${small.text.length}->${large.text.length} chars | " +
                "snapshot-retained=${format(retained.smallValue)}->${format(retained.largeValue)} bytes " +
                "growth=${ratio(retained.ratio)} | " +
                "check-allocated=${format(allocated.smallValue)}->${format(allocated.largeValue)} bytes " +
                "growth=${ratio(allocated.ratio)}",
        )
    }

    /**
     * One quantity's paired growth: [measure] at both scales, INTERLEAVED per pair, median of within-pair ratios.
     *
     * The ratified paired estimator, and interleaving earns less here than in a timing lane because a post-GC
     * byte delta barely drifts over a run. It is kept anyway: one estimator across every H5 boundary is worth
     * more than a local simplification, and the ratio is the only shape this boundary can carry as a bar.
     */
    private fun growthOf(samples: Int, small: Scale, large: Scale, measure: (Scale) -> Long): Growth {
        val smallBytes = LongArray(samples)
        val largeBytes = LongArray(samples)
        val ratios = DoubleArray(samples)
        repeat(samples) { pair ->
            smallBytes[pair] = measure(small)
            largeBytes[pair] = measure(large)
            ratios[pair] = largeBytes[pair].toDouble() / smallBytes[pair]
        }
        return Growth(
            ratio = median(ratios),
            smallValue = median(smallBytes).toDouble(),
            largeValue = median(largeBytes).toDouble(),
            smallN = small.programs,
            largeN = large.programs,
        )
    }

    /** One scale's model text, with its entry id, admitted once outside every measurement. */
    private fun admitted(programs: Int): Scale {
        val text = ProfileSynthesisTargetProfiles.modelJson(programs)
        val baseline = runChecker(ModelSource(text, diagnosticLabel = "memory-$programs"))
        require(baseline.findings.none { it.justifiesRejection }) {
            "the checker REFUSED the synthesized model at $programs programs, so every byte below would be " +
                "retained by a rejection rather than by an analysis: " +
                baseline.findings.filter { it.justifiesRejection }.map { "${it.code}@${it.at}" }.sorted()
        }
        val entry = checkNotNull(baseline.modelId) {
            "the synthesized model at $programs programs declares no id, so it was never admitted"
        }
        return Scale(programs, text, entry)
    }

    private data class Scale(val programs: Int, val text: String, val entry: String)

    /**
     * Post-GC used heap with the snapshot held live, minus post-GC used heap without it.
     *
     * The bracket is `baseline -> build -> live`, so the returned delta is closed before anything is dropped.
     * The release check that follows is a GATE with two jobs, and neither of them is this sample's figure: it
     * is §8's "release behavior" observation, and it is what keeps the NEXT sample's baseline clean — without
     * it, sample two would measure its own snapshot against a heap still holding sample one's.
     * `check`'s own transient garbage is out of the delta because both readings follow an explicit collection.
     */
    private fun retainedBytesOfOneSnapshot(scale: Scale): Long {
        val before = postGcUsedBytes()
        var snapshot: ConsistencySnapshot? =
            snapshotOf(listOf(ModelSource(scale.text, diagnosticLabel = "memory-${scale.programs}")), scale.entry)
        // Read the report so a lazily-built fact base cannot make the snapshot look cheaper than a consumer
        // holding one finds it — the same reason every timing arm reads its result.
        require(checkNotNull(snapshot).report.findings.size >= 0) { "unreachable, and the reason the report is read" }
        val withSnapshotLive = postGcUsedBytes()
        val watch = WeakReference(snapshot)
        snapshot = null
        require(releases(watch)) {
            "the snapshot was still reachable after being dropped, so this lane cannot measure a baseline and " +
                "the checker retains an analysis a consumer has released — which is the leak this gate exists " +
                "to catch rather than a measurement problem"
        }
        return withSnapshotLive - before
    }

    /** Thread-allocated bytes during one complete `check` — the estate's allocation quantity, not a heap delta. */
    private fun allocatedBytesOfOneCheck(text: String): Long {
        val thread = Thread.currentThread().threadId()
        val before = ALLOCATION.getThreadAllocatedBytes(thread)
        val report = runChecker(ModelSource(text, diagnosticLabel = "allocated"))
        val after = ALLOCATION.getThreadAllocatedBytes(thread)
        require(report.findings.size >= 0) { "unreachable, and the reason the report is read" }
        return after - before
    }

    /**
     * Refuses the whole lane when `System.gc()` cannot be shown to collect.
     *
     * `-XX:+DisableExplicitGC` turns every `System.gc()` into a no-op silently, and a post-GC reading taken
     * under it measures whatever the collector last happened to do. The estate's footprint worker gates on the
     * same fact for the same reason; being unable to measure is not the same as measuring zero.
     */
    private fun requireExplicitGcIsViable() {
        val watch = WeakReference(Any())
        require(releases(watch)) {
            "System.gc() did not produce observable collection, so no post-GC heap reading on this JVM means " +
                "anything — check for -XX:+DisableExplicitGC on the lane"
        }
    }

    /** Whether [watch]'s referent is gone within a bounded number of explicit collections. */
    private fun releases(watch: WeakReference<*>): Boolean {
        repeat(GC_ATTEMPTS) {
            if (watch.get() == null) return true
            System.gc()
        }
        return watch.get() == null
    }

    private fun postGcUsedBytes(): Long {
        repeat(GC_ATTEMPTS) { System.gc() }
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        return heap.used
    }

    private fun median(values: LongArray): Long = values.sortedArray().let { it[it.size / 2] }

    private fun median(values: DoubleArray): Double = values.sortedArray().let { it[it.size / 2] }

    private fun ratio(value: Double): String = "%.3fx".format(Locale.ROOT, value)

    private fun format(value: Double): String = String.format(Locale.ROOT, "%,.0f", value)

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-D$name=<value> is required" }

    private companion object {
        /** Bounded so a JVM that never collects fails the gate instead of spinning. */
        const val GC_ATTEMPTS = 4

        val ALLOCATION: ThreadMXBean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
            ?.also {
                require(it.isThreadAllocatedMemorySupported) {
                    "this JVM does not support per-thread allocation measurement"
                }
                if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
            }
            ?: throw IllegalStateException("the JVM does not expose com.sun.management.ThreadMXBean")
    }
}
