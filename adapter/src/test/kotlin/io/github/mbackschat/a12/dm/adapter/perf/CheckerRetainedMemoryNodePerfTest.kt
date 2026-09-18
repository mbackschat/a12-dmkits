package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5-j's plain-Node arm: what a Node consumer's heap does across repeated checks.
 *
 * **Its subject is RELEASE, not retention.** [CheckerRetainedMemoryPerfTest] holds a `ConsistencySnapshot`
 * live and reads the post-GC heap; this lane asks the other half of what §8 wants from the resources boundary
 * — whether a heap stays flat across many stateless checks or grows per call.
 *
 * **The reason it asks only that half EXPIRED on 2026-09-18, and the arm it blocked has since landed beside
 * it.** This lane was scoped to release because the shipped `a12-consistency.d.ts` exported two string-in
 * string-out functions, so a Node consumer could hold no analysis and retention had no analogue here.
 * `snapshotOfJson` then shipped a holdable handle, which is exactly the JVM arm's subject on this carrier, and
 * [CheckerRetainedSnapshotNodePerfTest] measures it — a separate lane, as this comment predicted, because the
 * two arms answer different halves of §8's row and gate different things. Nothing below is wrong; it is
 * narrower than the carrier now allows, which is why the pair exists rather than an edit
 * (`PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` "#h5j-node-retention").
 *
 * **It asserts no bound, and since 2026-09-18 that is DECIDED rather than pending.** Per H5's ordering rule
 * the collector came first and the bar was the owner's through IF137's six points; a flatness bound taken
 * from this lane's own first output is exactly what that rule forbids. The owner then declined a flatness
 * bound outright, because the only readings that could have set one were taken on a machine later found
 * contended — ratifying off them would repeat the error that voided that day's other figures. The trigger to
 * re-open is a second reading on a quiet machine.
 *
 * What it does assert is everything that decides whether the numbers MEAN
 * anything: the runtime is the pinned one, explicit collection observably reclaims, the model is one the
 * checker accepts (a reading over a refused model would measure rejecting), the report has the shape the other
 * arms measure, and both phases ran.
 *
 * **Two phases of the same size, so the SHAPE of any growth is visible in the output.** A per-call leak makes
 * the second phase's delta match the first; a one-off warm-up cost makes it collapse. That distinction needs
 * no bar to read, which is what makes an informative collector worth landing before one exists.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.memorynode", matches = "true")
class CheckerRetainedMemoryNodePerfTest {

    @Test
    fun whatANodeConsumersHeapDoesAcrossRepeatedChecks() {
        val node = Path.of(ColdLane.requiredProperty("perf.checker.memorynode.node"))
        val consumer = Path.of(ColdLane.requiredProperty("perf.checker.memorynode.project"))
        val checks = ColdLane.requiredProperty("perf.checker.memorynode.checks").toInt()
        val programs = ColdLane.requiredProperty("perf.checker.memorynode.n").toInt()
        NodeIdentity.requireSupported(
            probeVersion(node),
            ColdLane.requiredProperty("perf.checker.memorynode.major"),
        )
        require(programs > 0) { "perf.checker.memorynode.n must be positive" }
        require(checks > 0) { "perf.checker.memorynode.checks must be positive" }

        val model = stageModel(consumer, programs)
        val printed = runChild(node, consumer, model, checks)

        // Not a bound on the quantity — a check that the quantity was read at all. A zero here means a field
        // was missing or misparsed, which prints as a plausible-looking collector line and is the one failure
        // an informative lane could otherwise ship silently.
        check(printed.baseline > 0 && printed.afterFirst > 0 && printed.afterSecond > 0) {
            "a heap reading came back non-positive, so the child's output was not the shape this parses: " +
                "baseline=${printed.baseline} afterFirst=${printed.afterFirst} afterSecond=${printed.afterSecond}"
        }
        check(printed.modelBytes > 0 && printed.reportChars > 0) {
            "the child reported a ${printed.modelBytes}-byte model and a ${printed.reportChars}-character " +
                "report, so it did not check what this lane staged"
        }

        PerfHarness.progress(
            "[checker-memory-node] runtime=${printed.nodeVersion} programs=$programs checks-per-phase=$checks " +
                "model=${printed.modelBytes} bytes report=${printed.reportChars} chars | " +
                "baseline=${printed.baseline} afterFirst=${printed.afterFirst} " +
                "afterSecond=${printed.afterSecond} bytes | " +
                "first-delta=${printed.afterFirst - printed.baseline} " +
                "second-delta=${printed.afterSecond - printed.afterFirst} bytes",
        )
    }

    /**
     * The one child, run to completion, with every precondition read out of its own output.
     *
     * The GC gate is the child's because only the child can observe it; this side asserts that the child
     * REPORTED it, which is the half a parent can check. A run whose gate line is missing is a run whose
     * readings are of whatever happened to be uncollected.
     */
    private fun runChild(node: Path, consumer: Path, model: Path, checks: Int): Printed {
        val completed = ChildProcess.runWithFuse(
            command = listOf(
                node.toString(),
                // Without it `globalThis.gc` is undefined and the child refuses: a post-GC reading is the
                // whole quantity, so the flag is part of the measurement rather than a convenience.
                "--expose-gc",
                CHILD_SCRIPT,
                model.toString(),
                checks.toString(),
            ),
            // What makes `import 'a12-consistency'` resolve to the INSTALLED package rather than to anything
            // this build compiled: Node walks up from the importing file's directory.
            directory = consumer,
            timeoutSeconds = CHILD_TIMEOUT_SECONDS,
        ) { "the retained-memory child" }
        check(completed.exitCode == 0) { "the retained-memory child exited ${completed.exitCode}: ${completed.output}" }

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
                "rather than of checking: $rejecting"
        }
        val shape = requireNotNull(warmups.single()["status"]) { "no status in: ${warmups.single()}" } to
            ColdLane.strictBoolean(
                requireNotNull(warmups.single()["complete"]) { "no complete in: ${warmups.single()}" },
            )
        check(shape == ColdLane.EXPECTED_WIRE_SHAPE) {
            "the child returned $shape rather than ${ColdLane.EXPECTED_WIRE_SHAPE}, so this is not the " +
                "quantity the other arms measure"
        }

        val observations = completed.output.lineSequence().filter { it.startsWith("observation ") }
            .map { ColdLane.taggedFields(it, "observation") }.toList()
        check(observations.size == 1) {
            "the child printed ${observations.size} observations, not one: ${completed.output}"
        }
        val fields = observations.single()
        fun field(name: String) = requireNotNull(fields[name]) { "no $name in: $fields" }
        val version = ColdLane.printedFields(completed.output)["nodeVersion"]
        return Printed(
            modelBytes = field("modelBytes").toLong(),
            reportChars = field("reportChars").toDouble().toLong(),
            baseline = field("baseline").toLong(),
            afterFirst = field("afterFirst").toLong(),
            afterSecond = field("afterSecond").toLong(),
            nodeVersion = requireNotNull(version) { "no nodeVersion in: ${completed.output}" },
        )
    }

    /** The synthesized model, written where the consumer project can read it. */
    private fun stageModel(consumer: Path, programs: Int): Path {
        val directory = Files.createDirectories(consumer.resolve("build/retained-models"))
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

    private data class Printed(
        val modelBytes: Long,
        val reportChars: Long,
        val baseline: Long,
        val afterFirst: Long,
        val afterSecond: Long,
        val nodeVersion: String,
    )

    private companion object {
        /** The child's whole program, resolved against the staged consumer project. */
        const val CHILD_SCRIPT = "retained-memory.mjs"

        /** Liveness fuse for a stuck child, not a latency bar — one process runs every check. */
        const val CHILD_TIMEOUT_SECONDS = 1_800L

        const val PROBE_TIMEOUT_SECONDS = 60L
    }
}
