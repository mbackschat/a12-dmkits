package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5-j's plain-Node RETENTION arm: what holding one analysis costs a Node consumer's heap.
 *
 * **It is the JVM arm's own subject on a second carrier**, which is why it is a separate lane rather than a
 * third phase of [CheckerRetainedMemoryNodePerfTest]: that arm measures RELEASE across repeated stateless
 * checks, and its own KDoc says a retention arm is a slice of its own. [CheckerRetainedMemoryPerfTest] holds a
 * `ConsistencySnapshot` live and reads the post-GC heap at two scales; this asks the same question of a held
 * `ConsistencySnapshotHandle`, which did not exist in JavaScript until `snapshotOfJson` shipped on 2026-09-18.
 *
 * **Asserts no bound, and as of 2026-09-18 that is a DECIDED disposition rather than a pending one.** It was
 * first the ordering rule — a bound read off this lane's own first output is what [IF137]'s six points forbid
 * — and the owner then declined all four of this boundary's candidate budgets. The growth ratio was declined
 * for a reason worth having here rather than only in the plan: the invariant a `SUB_QUADRATIC_MAX` bar would
 * express is already decided by a COUNT. `Sweep.comparisons` is the enforcement locus and
 * `CellValidationSweepTest` the test that fails, both in `commonTest`, so they settle it deterministically on
 * every carrier — where this lane is `perf-bench`, property-gated, excluded from the release gate, and can
 * only fail once a constant is already large enough to hurt. The absolute byte, release-ratio and
 * heap-flatness candidates were declined with their own re-open triggers; see the plan's candidate table.
 *
 * What it does assert is everything deciding whether the numbers MEAN anything — the pinned runtime, an
 * observably reclaiming collector, a model the checker accepts, the wire shape the other arms measure, both
 * scales present, and the handle actually releasing.
 *
 * **Release is asserted here and is not a budget.** A held analysis that never comes back is a leak rather
 * than a footprint, and the two are indistinguishable from a retained byte count alone — so the child reads
 * the heap again after dropping the handle and this side requires most of the retained bytes back. The
 * threshold is deliberately loose: it separates "released" from "permanent", which is a question with a
 * right answer, rather than naming how much a snapshot may cost, which is the owner's to set.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.retainednode", matches = "true")
class CheckerRetainedSnapshotNodePerfTest {

    @Test
    fun whatHoldingOneAnalysisCostsANodeConsumersHeap() {
        val node = Path.of(ColdLane.requiredProperty("perf.checker.retainednode.node"))
        val consumer = Path.of(ColdLane.requiredProperty("perf.checker.retainednode.project"))
        val programs = ColdLane.requiredProperty("perf.checker.retainednode.n").toInt()
        NodeIdentity.requireSupported(
            probeVersion(node),
            ColdLane.requiredProperty("perf.checker.retainednode.major"),
        )
        require(programs > 0) { "perf.checker.retainednode.n must be positive" }

        val small = stageModel(consumer, programs)
        val large = stageModel(consumer, programs * SCALE_FACTOR)
        val printed = runChild(node, consumer, small, large)

        val smallRetained = printed.getValue("small").retainedBytes
        val largeRetained = printed.getValue("large").retainedBytes
        // Not a bound on the quantity — a check that the quantity was read at all. A non-positive retention
        // means the handle held nothing measurable, which prints as a plausible collector line and is the one
        // failure an informative lane could otherwise ship silently.
        check(smallRetained > 0 && largeRetained > 0) {
            "a held analysis retained $smallRetained / $largeRetained bytes, so this measured no analysis: " +
                printed.values.joinToString()
        }

        for ((scale, reading) in printed) {
            val returned = reading.withHeld - reading.afterRelease
            check(returned >= reading.retainedBytes * RELEASE_FRACTION) {
                "dropping the $scale handle returned $returned of ${reading.retainedBytes} retained bytes, " +
                    "so a held analysis is not released and this lane measured a LEAK rather than a footprint"
            }
        }

        PerfHarness.progress(
            "[checker-retained-snapshot-node] runtime=${printed.getValue("small").nodeVersion} " +
                "programs=$programs->${programs * SCALE_FACTOR} | " +
                "snapshot-retained=$smallRetained->$largeRetained bytes " +
                "growth=${format(largeRetained / smallRetained.toDouble())}x | " +
                "released=${printed.getValue("small").withHeld - printed.getValue("small").afterRelease}" +
                "->${printed.getValue("large").withHeld - printed.getValue("large").afterRelease} bytes",
        )
    }

    /**
     * The one child, run to completion, with every precondition read out of its own output.
     *
     * The GC gate is the child's because only the child can observe it; this side asserts that the child
     * REPORTED it, which is the half a parent can check. A run whose gate line is missing is a run whose
     * readings are of whatever happened to be uncollected.
     */
    private fun runChild(node: Path, consumer: Path, small: Path, large: Path): Map<String, Reading> {
        val completed = ChildProcess.runWithFuse(
            command = listOf(
                node.toString(),
                // Without it `globalThis.gc` is undefined and the child refuses: a post-GC reading is the
                // whole quantity, so the flag is part of the measurement rather than a convenience.
                "--expose-gc",
                CHILD_SCRIPT,
                small.toString(),
                large.toString(),
            ),
            // What makes `import 'a12-consistency'` resolve to the INSTALLED package rather than to anything
            // this build compiled: Node walks up from the importing file's directory.
            directory = consumer,
            timeoutSeconds = CHILD_TIMEOUT_SECONDS,
        ) { "the retained-snapshot child" }
        check(completed.exitCode == 0) {
            "the retained-snapshot child exited ${completed.exitCode}: ${completed.output}"
        }

        val gate = completed.output.lineSequence().filter { it.startsWith("gate ") }
            .map { ColdLane.taggedFields(it, "gate") }.toList()
        check(gate.size == 1) {
            "the child printed ${gate.size} gc-viability lines, so the heap readings below are unqualified: " +
                completed.output
        }

        val warmups = completed.output.lineSequence().filter { it.startsWith("warmup ") }
            .map { ColdLane.taggedFields(it, "warmup") }.toList()
        check(warmups.size == 1) { "the child warmed ${warmups.size} times, not once: ${completed.output}" }
        val rejecting = warmups.single()["rejecting"].orEmpty().split(',').filter { it.isNotBlank() }
        check(rejecting.isEmpty()) {
            "the checker REFUSED the synthesized model, so this lane would measure the cost of rejecting " +
                "rather than of holding: $rejecting"
        }
        val shape = requireNotNull(warmups.single()["status"]) { "no status in: ${warmups.single()}" } to
            ColdLane.strictBoolean(
                requireNotNull(warmups.single()["complete"]) { "no complete in: ${warmups.single()}" },
            )
        check(shape == ColdLane.EXPECTED_WIRE_SHAPE) {
            "the child returned $shape rather than ${ColdLane.EXPECTED_WIRE_SHAPE}, so this is not the " +
                "quantity the other arms measure"
        }

        val version = requireNotNull(ColdLane.printedFields(completed.output)["nodeVersion"]) {
            "no nodeVersion in: ${completed.output}"
        }
        val readings = completed.output.lineSequence().filter { it.startsWith("observation ") }
            .map { ColdLane.taggedFields(it, "observation") }
            .map { fields ->
                fun field(name: String) = requireNotNull(fields[name]) { "no $name in: $fields" }
                requireNotNull(fields["scale"]) { "no scale in: $fields" } to Reading(
                    modelBytes = field("modelBytes").toLong(),
                    reportChars = field("reportChars").toLong(),
                    baseline = field("baseline").toLong(),
                    withHeld = field("withHeld").toLong(),
                    afterRelease = field("afterRelease").toLong(),
                    nodeVersion = version,
                )
            }
            .toMap()
        check(readings.keys == setOf("small", "large")) {
            "the child reported scales ${readings.keys}, so there is no pair to compare: ${completed.output}"
        }
        check(readings.values.all { it.modelBytes > 0 && it.reportChars > 0 }) {
            "a scale reported an empty model or an empty report, so it did not check what this lane staged: " +
                readings
        }
        return readings
    }

    /** The synthesized model, written where the consumer project can read it. */
    private fun stageModel(consumer: Path, programs: Int): Path {
        val directory = Files.createDirectories(consumer.resolve("build/retained-snapshot-models"))
        val target = directory.resolve("synth-$programs.dm.json")
        Files.writeString(target, ProfileSynthesisTargetProfiles.modelJson(programs))
        return target
    }

    /** What the pin is checked against, asked of the binary rather than inferred from its path. */
    private fun probeVersion(node: Path): String {
        val completed = ChildProcess.runWithFuse(
            command = listOf(node.toString(), "--version"),
            timeoutSeconds = PROBE_TIMEOUT_SECONDS,
        ) { "the version probe of $node" }
        check(completed.exitCode == 0) { "could not ask $node for its version: ${completed.output}" }
        return completed.output.trim()
    }

    private fun format(value: Double): String = "%.3f".format(Locale.ROOT, value)

    private data class Reading(
        val modelBytes: Long,
        val reportChars: Long,
        val baseline: Long,
        val withHeld: Long,
        val afterRelease: Long,
        val nodeVersion: String,
    ) {
        /** What the handle itself costs — the settled heap with it live, less the settled heap without it. */
        val retainedBytes: Long get() = withHeld - baseline
    }

    private companion object {
        /** The child's whole program, resolved against the staged consumer project. */
        const val CHILD_SCRIPT = "retained-snapshot.mjs"

        /** The JVM arm's own span, so the two carriers' growth ratios are the same quantity. */
        const val SCALE_FACTOR = 4

        /**
         * How much of a dropped handle must come back for it to count as released.
         *
         * Loose on purpose. It separates "released" from "permanent" — a question with a right answer — and
         * deliberately does NOT name how much a snapshot may cost, which is the owner's to ratify. The slack
         * absorbs what a settled heap legitimately keeps: interned strings, the module's own warm state, and
         * whatever the engine has not yet handed back to the allocator.
         */
        const val RELEASE_FRACTION = 0.5

        /** Liveness fuse for a stuck child, not a latency bar — one process builds both snapshots. */
        const val CHILD_TIMEOUT_SECONDS = 1_800L

        const val PROBE_TIMEOUT_SECONDS = 60L
    }
}
