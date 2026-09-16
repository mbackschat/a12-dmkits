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
 * **It records and asserts nothing.** A collector coming first with informative output is H5's ordering rule,
 * and a budget taken from this lane's own first figure is what that rule forbids. The ratified `>=5 forks for
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

        // Both scales are sampled as INTERLEAVED pairs with the median of within-pair ratios, which is the
        // ratified paired estimator the timing lanes use. A post-GC byte delta drifts far less over a run than a
        // duration does, so the interleaving buys less here than it does there — it is kept because one
        // estimator across every H5 boundary is worth more than a local simplification, and because a growth
        // ratio is the only shape this boundary could carry if bytes ever turn out to be machine-dependent.
        val retainedSmall = LongArray(samples)
        val retainedLarge = LongArray(samples)
        val allocatedSmall = LongArray(samples)
        val allocatedLarge = LongArray(samples)
        val retainedRatio = DoubleArray(samples)
        val allocatedRatio = DoubleArray(samples)
        repeat(samples) { pair ->
            retainedSmall[pair] = retainedBytesOfOneSnapshot(small)
            retainedLarge[pair] = retainedBytesOfOneSnapshot(large)
            allocatedSmall[pair] = allocatedBytesOfOneCheck(small.text)
            allocatedLarge[pair] = allocatedBytesOfOneCheck(large.text)
            retainedRatio[pair] = retainedLarge[pair].toDouble() / retainedSmall[pair]
            allocatedRatio[pair] = allocatedLarge[pair].toDouble() / allocatedSmall[pair]
        }

        PerfHarness.progress(
            "[checker-memory] programs=$programs->${programs * 4} samples=$samples " +
                "model-text=${small.text.length}->${large.text.length} chars | " +
                "snapshot-retained=${format(median(retainedSmall))}->${format(median(retainedLarge))} bytes " +
                "growth=${ratio(median(retainedRatio))} | " +
                "check-allocated=${format(median(allocatedSmall))}->${format(median(allocatedLarge))} bytes " +
                "growth=${ratio(median(allocatedRatio))}",
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

    private fun format(value: Long): String = String.format(Locale.ROOT, "%,d", value)

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
