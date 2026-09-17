package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5-h's plain-Node arm: how the checker's PREPARED cost grows with declarations, measured on the host §8's
 * prepared row names beside the JVM and nothing had measured.
 *
 * **Why this arm exists at all.** §8's prepared row reads host-neutral, and the boundary table's silence on
 * hosts is the weakest of the three statements that govern H5 — the owner's hard-acceptance section attaches
 * "JVM, plain Node and browser consumers" to the whole budget list, memory alone carrying a feasibility
 * qualifier. Under that reading H5 is a 5 × 3 grid, and this cell was one of six unmeasured
 * ([the record](../../../../../../../../../../../docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md)).
 *
 * **It clocks the SAME quantity [CheckerGrowthPerfTest] does, which is what makes the two comparable.** That
 * lane times `check(ModelSource(text))` on a runtime whose class loading and reflective setup a preflight has
 * already paid for: a repeated whole-model check, not a snapshot-reuse call. So the analogue is
 * `checkModelJson` after a full warm-up per scale — reachable through the checker's published two-function JS
 * surface exactly as it stands. A prepared or snapshot entry point is deliberately NOT exported to JavaScript
 * (`JsApi.kt` carries the reasons), and timing one through an unexported Kotlin function would report a cost
 * no Node consumer can obtain.
 *
 * **ONE warm process for the whole run**, where the cold arm pays a launch per observation: warmth is this
 * boundary's subject, so relaunching would measure precisely what it excludes. Drift therefore lands inside a
 * single process rather than across many, which is what the interleaved visit order is for — both members of a
 * pair meet the same load slice.
 *
 * **The bar is the already-ratified growth ratio and nothing else.** `PerfBounds.SUB_QUADRATIC_MAX` was
 * ratified as a mechanism on 2026-09-16, before this host had a lane, so asserting it here is not a bar fitted
 * to what this lane first produced. No duration is asserted and no anchor written. Asserting the ratio on a new
 * carrier is a scope transfer, so it rests on this arm's own measurement rather than on the JVM lane's.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.preparednode", matches = "true")
class CheckerPreparedNodePerfTest {

    @Test
    fun thePreparedCheckerGrowsBelowTheCatastropheFloorOnPlainNode() {
        val node = Path.of(ColdLane.requiredProperty("perf.checker.preparednode.node"))
        val consumer = Path.of(ColdLane.requiredProperty("perf.checker.preparednode.project"))
        val pairs = ColdLane.requiredProperty("perf.checker.preparednode.pairs").toInt()
        val programs = ColdLane.requiredProperty("perf.checker.preparednode.n").toInt()
        NodeIdentity.requireSupported(
            probeVersion(node),
            ColdLane.requiredProperty("perf.checker.preparednode.major"),
        )
        require(programs > 0) { "perf.checker.preparednode.n must be positive" }
        require(pairs >= ColdLane.MINIMUM_OBSERVATIONS) {
            "the prepared boundary's floor is ${ColdLane.MINIMUM_OBSERVATIONS} observations per scale; $pairs " +
                "would be a different measurement"
        }
        require(pairs % 2 == 1) {
            "the ratified estimator medians the within-pair ratios, so an even $pairs would make that median " +
                "the midpoint of two ratios nobody observed"
        }

        val scales = listOf(programs, programs * 4)
        val models = stageModels(consumer, scales)

        PerfHarness.assertGrowthBelowCatastropheFloor(
            "portable checker PREPARED N/4N over model declarations, plain Node",
            "the same whole-model declaration walk the JVM prepared lane guards — the shape that made this " +
                "check cubic in 2026-09 and is invisible to every correctness test",
        ) { preparedGrowth(node, consumer, scales, models, pairs) }
    }

    /**
     * One estimate: [pairs] interleaved N/4N observations inside a single warm process.
     *
     * **The sampling lives inside this function, and that is load-bearing.** The assertion helper re-measures on
     * a breach, so an estimate collected once outside it would be drained by the first attempt and the second
     * would pop an empty list — measured as a bare `NoSuchElementException` when H5-g was built, on a lane whose
     * happy path was green ([the record](../../../../../../../../../../../docs/PORTABLE-CONSISTENCY-RESULTS.md#cold-browser-2026-09-17)).
     * Re-measuring is also the only correct semantics: reusing one sample set would return the identical ratio
     * every time, so a re-measurement would confirm itself.
     */
    private fun preparedGrowth(
        node: Path,
        consumer: Path,
        scales: List<Int>,
        models: Map<Int, Path>,
        pairs: Int,
    ): Growth {
        val visits = (1..pairs).flatMap { scales }
        val printed = runChild(node, consumer, models, visits)
        val byScale = scales.associateWith { scale ->
            printed.observations.filter { it.scale == scale }.map { it.checkNanos / 1_000_000.0 }
                .toMutableList()
        }

        scales.forEach { scale ->
            val observations = byScale.getValue(scale)
            // Per SCALE, not just in total: a child emitting one extra small and one missing large keeps the
            // total at `2 × pairs` and then dies on `removeFirst()` below with a bare exception.
            check(observations.size == pairs) {
                "the child produced ${observations.size} observation(s) at $scale programs, not $pairs, so the " +
                    "pairs the estimator is about to reconstruct do not exist"
            }
            val atScale = printed.observations.filter { it.scale == scale }
            ColdLane.reportNanos("checker-prepared-node", "check", scale, atScale.map { it.checkNanos })
            // Reported rather than asserted: this arm's `check` includes rendering the report to JSON, which
            // the JVM lane's quantity excludes, so a reader comparing the two ratios needs to know whether the
            // report itself grows. One line of evidence beats a paragraph of hedging.
            val chars = atScale.map { it.reportChars }.distinct().sorted()
            PerfHarness.progress(
                "[checker-prepared-node] reportChars programs=$scale distinct=${chars.size} " +
                    "min=${chars.first()} max=${chars.last()}",
            )
        }

        return PerfHarness.pairedGrowth(scales.first(), scales.last(), pairs) { scale ->
            byScale.getValue(scale).removeFirst()
        }.also { growth ->
            PerfHarness.progress(
                "[checker-prepared-node] runtime=${printed.nodeVersion} " +
                    "paired programs=${scales.first()}->${scales.last()} " +
                    "median=${ColdLane.format(growth.smallValue)}->${ColdLane.format(growth.largeValue)} ms " +
                    "paired-ratio=${ColdLane.format(growth.ratio)}x",
            )
        }
    }

    /**
     * The one child, run to completion, with its warm-up lines checked before any observation is believed.
     *
     * The warm-up doubles as the silence AND shape preflight, the arrangement the JVM lane uses: a ratio taken
     * over a model the checker refuses would compare rejection costs, and an empty rejecting set is also what a
     * report with no findings at all looks like.
     */
    private fun runChild(node: Path, consumer: Path, models: Map<Int, Path>, visits: List<Int>): Printed {
        val arguments = models.map { (scale, path) -> "$scale=$path" } + visits.joinToString(",")
        val completed = ChildProcess.runWithFuse(
            command = listOf(node.toString(), CHILD_SCRIPT) + arguments,
            // What makes `import 'a12-consistency'` resolve to the INSTALLED package rather than to anything
            // this build compiled: Node walks up from the importing file's directory.
            directory = consumer,
            timeoutSeconds = CHILD_TIMEOUT_SECONDS,
        ) { "the prepared-growth child" }
        check(completed.exitCode == 0) { "the prepared-growth child exited ${completed.exitCode}: ${completed.output}" }

        val warmups = completed.output.lineSequence().filter { it.startsWith("warmup ") }
            .map { ColdLane.taggedFields(it, "warmup") }
            .toList()
        check(warmups.size == models.size) {
            "the child warmed ${warmups.size} scale(s) for ${models.size} model(s), so at least one " +
                "observation was taken on a runtime this lane never warmed: ${completed.output}"
        }
        warmups.forEach { fields ->
            val rejecting = fields["rejecting"].orEmpty().split(',').filter { it.isNotBlank() }
            check(rejecting.isEmpty()) {
                "the checker REFUSED the synthesized model, so this lane would compare the cost of rejecting " +
                    "rather than of checking: $rejecting"
            }
            val shape = requireNotNull(fields["status"]) { "no status in: $fields" } to
                ColdLane.strictBoolean(requireNotNull(fields["complete"]) { "no complete in: $fields" })
            check(shape == ColdLane.EXPECTED_WIRE_SHAPE) {
                "the child returned $shape rather than ${ColdLane.EXPECTED_WIRE_SHAPE}, so this quantity is " +
                    "not the one the other prepared arms measure"
            }
        }

        val observations = completed.output.lineSequence().filter { it.startsWith("observation ") }
            .map { ColdLane.taggedFields(it, "observation") }
            .map { fields ->
                Observation(
                    scale = requireNotNull(fields["scale"]) { "no scale in: $fields" }.toInt(),
                    checkNanos = requireNotNull(fields["checkNanos"]) { "no checkNanos in: $fields" }.toLong(),
                    reportChars = requireNotNull(fields["reportChars"]) { "no reportChars in: $fields" }.toInt(),
                )
            }
            .toList()
        check(observations.size == visits.size) {
            "the child printed ${observations.size} observations for ${visits.size} requested visits: " +
                completed.output
        }
        val version = ColdLane.printedFields(completed.output)["nodeVersion"]
        return Printed(observations, requireNotNull(version) { "no nodeVersion in: ${completed.output}" })
    }

    /** The two synthesized models, written where the consumer project can read them. */
    private fun stageModels(consumer: Path, scales: List<Int>): Map<Int, Path> {
        val directory = Files.createDirectories(consumer.resolve("build/prepared-models"))
        return scales.associateWith { scale ->
            val target = directory.resolve("synth-$scale.dm.json")
            Files.writeString(target, ProfileSynthesisTargetProfiles.modelJson(scale))
            target
        }
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

    private data class Observation(val scale: Int, val checkNanos: Long, val reportChars: Int)

    private data class Printed(val observations: List<Observation>, val nodeVersion: String)

    private companion object {
        /** The child's whole program, resolved against the staged consumer project. */
        const val CHILD_SCRIPT = "prepared-growth.mjs"

        /** Liveness fuse for a stuck child, not a latency bar — one process now runs every observation. */
        const val CHILD_TIMEOUT_SECONDS = 1_800L

        const val PROBE_TIMEOUT_SECONDS = 60L
    }
}
