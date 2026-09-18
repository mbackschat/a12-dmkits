package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5-i's plain-Node arm: what holding one analysis saves a Node consumer per keystroke.
 *
 * **This cell was not measurable until the surface widened, and the reason is worth keeping.** Its quantity is
 * a snapshot-reuse ratio, and until 2026-09-18 no stateful entry point was exported to JavaScript — so the
 * cell was recorded as deferred rather than measured, with an exported handle named as its re-open trigger
 * (`docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5i-trigger-fired`). Measuring `checkEditJson` instead
 * would have reported the cost of two checks: a number that looks like this cell and answers a different
 * question.
 *
 * **Exactly ONE of the JVM lane's three arms carries a bar, and this arm is that one.** [CheckerAfterEditPerfTest]
 * measures build-snapshot and saved-edit too; both sit at ~1.0 by construction, so a ceiling on either would
 * have to come from a lane's own spread. The keystroke ceiling is different: its basis is a WORK COUNT written
 * before any lane existed — `checkDraft` performs one admission, one preparation and two checks per call where
 * a snapshot makes the same keystroke cost one check — so the number is fixed by the feature rather than by a
 * measurement.
 *
 * **Asserting that ceiling on a new carrier is a SCOPE TRANSFER, so it rests on this arm's own measurement.**
 * The JVM arm's 0.474–0.502x says nothing about a JS engine's object churn or its JSON rendering, and a bar
 * holding on one carrier is a claim about the other rather than an inheritance. What the two arms share is the
 * bar's basis, not its evidence.
 *
 * **One warm process, interleaved pairs, and no statistic computed in the child** — the same three choices the
 * prepared Node arm makes, for the same reasons: warmth is the subject, drift must land in both terms, and a
 * second implementation of the ratified estimator could drift from it silently.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.aftereditnode", matches = "true")
class CheckerAfterEditNodePerfTest {

    @Test
    fun aKeystrokeOffAHeldAnalysisStaysBelowTheRatifiedCeilingOnPlainNode() {
        val node = Path.of(ColdLane.requiredProperty("perf.checker.aftereditnode.node"))
        val consumer = Path.of(ColdLane.requiredProperty("perf.checker.aftereditnode.project"))
        val pairs = ColdLane.requiredProperty("perf.checker.aftereditnode.pairs").toInt()
        val programs = ColdLane.requiredProperty("perf.checker.aftereditnode.n").toInt()
        NodeIdentity.requireSupported(
            probeVersion(node),
            ColdLane.requiredProperty("perf.checker.aftereditnode.major"),
        )
        require(programs > 0) { "perf.checker.aftereditnode.n must be positive" }
        require(pairs >= ColdLane.MINIMUM_OBSERVATIONS) {
            "this boundary's floor is ${ColdLane.MINIMUM_OBSERVATIONS} observations; $pairs would be a " +
                "different measurement"
        }
        require(pairs % 2 == 1) {
            "the ratified estimator medians the within-pair ratios, so an even $pairs would make that median " +
                "the midpoint of two ratios nobody observed"
        }

        val model = stageModel(consumer, programs)

        // Inside the assertion helper's lambda, because it RE-MEASURES on a breach: an estimate collected once
        // outside would be reused, and a re-measurement that returns the identical ratio confirms itself.
        val reuse = PerfHarness.assertReuseRatioBelowCeiling(
            "a keystroke off a held analysis against the stateless draft at $programs programs, plain Node",
            ceiling = KEYSTROKE_CEILING,
        ) { keystrokeRatio(node, consumer, model, pairs) }

        PerfHarness.progress(
            "[checker-after-edit-node] programs=$programs pairs=$pairs " +
                "keystroke=${format(reuse.ratio)}x " +
                "(stateless ${format(reuse.statelessMs)} ms, stateful ${format(reuse.statefulMs)} ms)",
        )
    }

    /** One estimate: [pairs] interleaved stateless/stateful observations inside a single warm process. */
    private fun keystrokeRatio(node: Path, consumer: Path, model: Path, pairs: Int): Reuse {
        val printed = runChild(node, consumer, model, pairs)
        val ratios = DoubleArray(printed.size) { printed[it].statefulNanos / printed[it].statelessNanos.toDouble() }
        val statelessMs = DoubleArray(printed.size) { printed[it].statelessNanos / 1_000_000.0 }
        val statefulMs = DoubleArray(printed.size) { printed[it].statefulNanos / 1_000_000.0 }
        return Reuse(PerfHarness.median(ratios), PerfHarness.median(statelessMs), PerfHarness.median(statefulMs))
    }

    /**
     * The one child, run to completion, with every precondition read out of its own output.
     *
     * Both preflights are the CHILD's to perform and the parent's to insist on: only the child holds the
     * handle, and a parent that merely hoped the child checked would accept a run over a refused model or over
     * two disagreeing calls — the two ways this ratio becomes meaningless while still looking like a number.
     */
    private fun runChild(node: Path, consumer: Path, model: Path, pairs: Int): List<Observation> {
        val completed = ChildProcess.runWithFuse(
            command = listOf(node.toString(), CHILD_SCRIPT, model.toString(), pairs.toString()),
            // What makes `import 'a12-consistency'` resolve to the INSTALLED package rather than to anything
            // this build compiled: Node walks up from the importing file's directory.
            directory = consumer,
            timeoutSeconds = CHILD_TIMEOUT_SECONDS,
        ) { "the after-edit child" }
        check(completed.exitCode == 0) { "the after-edit child exited ${completed.exitCode}: ${completed.output}" }

        val warmups = completed.output.lineSequence().filter { it.startsWith("warmup ") }
            .map { ColdLane.taggedFields(it, "warmup") }.toList()
        check(warmups.size == 1) { "the child warmed ${warmups.size} times, not once: ${completed.output}" }
        val rejecting = warmups.single()["rejecting"].orEmpty().split(',').filter { it.isNotBlank() }
        check(rejecting.isEmpty()) {
            "the checker REFUSED the synthesized model, so every pair below would compare two rejection " +
                "paths rather than two ways to check: $rejecting"
        }

        val agreement = completed.output.lineSequence().filter { it.startsWith("agreement ") }
            .map { ColdLane.taggedFields(it, "agreement") }.toList()
        check(agreement.size == 1) {
            "the child reported ${agreement.size} agreement lines, so the two calls are unqualified: " +
                completed.output
        }
        check(ColdLane.strictBoolean(requireNotNull(agreement.single()["identical"]))) {
            "the held and stateless drafts returned different payloads, so no ratio between them compares " +
                "two ways to reach ONE answer: ${agreement.single()}"
        }
        // A refused draft reports a gap and attributes nothing — fast, uniform, and a measurement of nothing.
        check(agreement.single()["gaps"] == "0") {
            "the draft was REFUSED rather than checked (${agreement.single()["gaps"]} gap(s)), so the " +
                "declaration path this lane names is not one this model declares: ${agreement.single()}"
        }

        // The reuse counters, ASSERTED and then printed. A ratio above the ceiling has two possible
        // mechanisms — the reuse is not happening, or it is happening and costs more than it saves — and they
        // are indistinguishable from a duration alone. Asserting that reuse occurred makes the failure
        // message name the second mechanism rather than leaving a reader to guess between them, and it stops
        // a future green run from passing on a build that reuses nothing.
        val reuse = completed.output.lineSequence().filter { it.startsWith("reuse ") }
            .map { ColdLane.taggedFields(it, "reuse") }.toList()
        check(reuse.size == 1) {
            "the child reported ${reuse.size} reuse lines, so this ratio cannot be attributed to a mechanism: " +
                completed.output
        }
        val reused = requireNotNull(reuse.single()["familiesReused"]).toInt()
        check(ColdLane.strictBoolean(requireNotNull(reuse.single()["preparationReused"])) && reused > 0) {
            "the held analysis re-prepared or reused no family verdict at all, so what this lane measured is " +
                "a snapshot that is not reusing rather than the cost of reuse: ${reuse.single()}"
        }
        PerfHarness.progress(
            "[checker-after-edit-node] reuse familiesReused=$reused " +
                "familiesRecomputed=${reuse.single()["familiesRecomputed"]} " +
                "preparationReused=${reuse.single()["preparationReused"]} " +
                "baselineReportReused=${reuse.single()["baselineReportReused"]}",
        )

        val observations = completed.output.lineSequence().filter { it.startsWith("observation ") }
            .map { ColdLane.taggedFields(it, "observation") }
            .map {
                Observation(
                    statelessNanos = requireNotNull(it["stateless"]).toLong(),
                    statefulNanos = requireNotNull(it["stateful"]).toLong(),
                )
            }
            .toList()
        check(observations.size == pairs) {
            "the child produced ${observations.size} pair(s), not $pairs, so the estimator would median a " +
                "set nobody asked for: ${completed.output}"
        }
        // Zero would mean the clock did not advance across a whole check, which on this quantity means the
        // call was elided rather than that it was fast.
        check(observations.all { it.statelessNanos > 0 && it.statefulNanos > 0 }) {
            "a pair reported a non-positive duration, so one of the two calls was not measured: $observations"
        }
        return observations
    }

    private fun stageModel(consumer: Path, programs: Int): Path {
        val directory = Files.createDirectories(consumer.resolve("build/after-edit-models"))
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

    private data class Observation(val statelessNanos: Long, val statefulNanos: Long)

    private companion object {
        /**
         * The same ceiling the JVM arm asserts, and for the same BASIS rather than by inheritance.
         *
         * A ratio at or above `1.0` means the held analysis saves this carrier nothing and a Node editor has
         * no reason to hold one — the feature fixes the number, not a measurement. Deliberately not tightened
         * toward whatever this arm first produces, which is the fitting IF137's promotion gate exists to
         * prevent.
         */
        const val KEYSTROKE_CEILING = 1.0
        const val CHILD_SCRIPT = "after-edit.mjs"
        const val CHILD_TIMEOUT_SECONDS = 600L
        const val PROBE_TIMEOUT_SECONDS = 30L
    }
}
