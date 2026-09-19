package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5-i's BROWSER arm: what holding one analysis saves a browser consumer per keystroke.
 *
 * **The second of the two cells §8's after-edit row left unmeasured**, and like its Node sibling it could not
 * exist before 2026-09-18: the quantity is a snapshot-reuse ratio, and no stateful entry point was exported to
 * JavaScript to hold anything with ([the trigger firing](
 * ../../../../../../../../../../../docs/archived/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md)).
 *
 * **It asserts the ratified keystroke ceiling on THIS carrier's own measurement.** A bar holding on the JVM
 * and on Node is a claim about a browser rather than an inheritance — a different engine moves more than one
 * variable — so nothing here rests on either sibling's figure. What the three arms share is the bar's BASIS: a
 * work count written before any lane existed, which says a held analysis that saves nothing gives an editor no
 * reason to hold one.
 *
 * **One context holding ONE handle for the whole campaign**, which is the thing being measured: a page that
 * re-created the handle per sample would destroy the quantity while still publishing plausible numbers. The
 * page's two preflights are what keep the ratio meaningful, and this lane refuses without both — a model the
 * checker rejects would compare two rejection paths, and two calls that disagree would compare different
 * answers rather than two ways to reach one.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.aftereditbrowser", matches = "true")
class CheckerAfterEditBrowserPerfTest {

    @Test
    fun aKeystrokeOffAHeldAnalysisStaysBelowTheRatifiedCeilingInARealBrowser() {
        val consumer = Path.of(ColdLane.requiredProperty("perf.checker.aftereditbrowser.project"))
        val pnpm = Path.of(ColdLane.requiredProperty("perf.checker.aftereditbrowser.pnpm"))
        val node = Path.of(ColdLane.requiredProperty("perf.checker.aftereditbrowser.node"))
        val pairs = ColdLane.requiredProperty("perf.checker.aftereditbrowser.pairs").toInt()
        val programs = ColdLane.requiredProperty("perf.checker.aftereditbrowser.n").toInt()
        require(pairs >= ColdLane.MINIMUM_OBSERVATIONS) {
            "this boundary's floor is ${ColdLane.MINIMUM_OBSERVATIONS} observations, not $pairs"
        }
        // The ratified estimator medians the within-pair ratios, so an odd count makes that median a ratio
        // actually observed rather than the midpoint of two that were not.
        require(pairs % 2 == 1) { "the paired estimator needs an odd pair count, not $pairs" }
        require(programs > 0) { "perf.checker.aftereditbrowser.n must be positive" }

        val served = BrowserConsumerLane.stageWorkloads(consumer, listOf(programs)).getValue(programs)
        // Outside the estimate: the bundle is part of no observation, and rebuilding it per re-measurement
        // attempt would add a minute to each for nothing.
        BrowserConsumerLane.buildBundle(consumer, pnpm, CHILD_TIMEOUT_SECONDS)

        val reuse = PerfHarness.assertReuseRatioBelowCeiling(
            "a keystroke off a held analysis against the stateless draft at $programs programs, real browser",
            ceiling = KEYSTROKE_CEILING,
        ) { keystrokeRatio(consumer, node, served, pairs, programs) }

        PerfHarness.progress(
            "[checker-after-edit-browser] programs=$programs pairs=$pairs " +
                "keystroke=${ColdLane.format(reuse.ratio)}x " +
                "(stateless ${ColdLane.format(reuse.statelessMs)} ms, " +
                "stateful ${ColdLane.format(reuse.statefulMs)} ms)",
        )
    }

    /**
     * One estimate: a fresh campaign, its two preflights, and the median of the within-pair ratios.
     *
     * **Sampling belongs inside this function**, for the reason the cold browser arm's control established:
     * the assertion helper re-measures on a breach, so an estimate collected once outside would be drained by
     * attempt one. Re-measuring is also the only correct semantics — reusing one sample set returns the
     * identical ratio, so the re-measurement would confirm itself.
     */
    private fun keystrokeRatio(
        consumer: Path,
        node: Path,
        served: String,
        pairs: Int,
        programs: Int,
    ): Reuse {
        val campaign = sample(consumer, node, served, pairs)

        check(campaign.rejecting.isEmpty()) {
            "the checker REFUSED the synthesized model, so this campaign would compare the cost of rejecting " +
                "rather than of checking: ${campaign.rejecting}"
        }
        check(campaign.status to campaign.complete == ColdLane.EXPECTED_WIRE_SHAPE) {
            "the page returned ${campaign.status}/${campaign.complete} rather than " +
                "${ColdLane.EXPECTED_WIRE_SHAPE}, so this quantity is not the one the other arms measure"
        }
        check(campaign.identical) {
            "the held and stateless drafts returned different payloads, so no ratio between them compares two " +
                "ways to reach ONE answer"
        }
        // A refused draft reports a gap and attributes nothing — fast, uniform, and a measurement of nothing.
        check(campaign.gaps == 0) {
            "the draft was REFUSED rather than checked (${campaign.gaps} gap(s)), so the declaration path this " +
                "lane names is not one the synthesized model declares"
        }

        val browsers = campaign.observations.map { it.browserVersion }.distinct().sorted()
        check(browsers.size == 1 && browsers.single().isNotBlank()) {
            "the observations carry no single non-blank browser build, so these numbers are unattributable: " +
                browsers
        }
        check(campaign.observations.size == pairs) {
            "the page published ${campaign.observations.size} pair(s), not $pairs, so the estimator would " +
                "median a set nobody asked for"
        }
        // Zero would mean the clock did not advance across a whole check, which on this quantity means the
        // call was elided rather than that it was fast. A browser's `performance.now()` is coarser than
        // `hrtime`, so this is the arm where it could plausibly happen.
        check(campaign.observations.all { it.statelessMs > 0.0 && it.statefulMs > 0.0 }) {
            "a pair reported a non-positive duration, so one of the two calls was not measured"
        }

        // `programs`, not `pairs`: the helper's third argument is the model SIZE the observations were taken
        // at, which is what identifies a figure in the record — the sample count is already in the label.
        ColdLane.report(
            "checker-after-edit-browser",
            "stateless",
            programs,
            campaign.observations.map { it.statelessMs },
        )
        ColdLane.report(
            "checker-after-edit-browser",
            "stateful",
            programs,
            campaign.observations.map { it.statefulMs },
        )
        PerfHarness.progress(
            // `Chrome/` prefixed, as the sibling arms do: `browser.version()` returns a bare number, and a
            // bare number in a record is the kind of figure nobody can identify a year later.
            "[checker-after-edit-browser] runtime=Chrome/${browsers.single()}",
        )

        val ratios = campaign.observations.map { it.statefulMs / it.statelessMs }.toDoubleArray()
        return Reuse(
            PerfHarness.median(ratios),
            PerfHarness.median(campaign.observations.map { it.statelessMs }.toDoubleArray()),
            PerfHarness.median(campaign.observations.map { it.statefulMs }.toDoubleArray()),
        )
    }

    /** Run the sampler once and read back its preflight and observation lines. */
    private fun sample(consumer: Path, node: Path, served: String, pairs: Int): Campaign {
        val output = BrowserConsumerLane.runToCompletion(
            consumer,
            listOf(node.toString(), SAMPLER, served, pairs.toString()),
            "the browser after-edit sampler",
            node,
            CHILD_TIMEOUT_SECONDS,
        )

        val warmups = output.lineSequence().filter { it.startsWith("warmup ") }
            .map { ColdLane.taggedFields(it, "warmup") }.toList()
        check(warmups.size == 1) { "the page warmed ${warmups.size} times, not once: $output" }
        val agreements = output.lineSequence().filter { it.startsWith("agreement ") }
            .map { ColdLane.taggedFields(it, "agreement") }.toList()
        check(agreements.size == 1) {
            "the page published ${agreements.size} agreement lines, so the two calls are unqualified: $output"
        }

        val observations = output.lineSequence().filter { it.startsWith("observation ") }.map { line ->
            val fields = ColdLane.taggedFields(line, "observation")
            Observation(
                statelessMs = requireNotNull(fields["stateless"]) { "no stateless in: $line" }.toDouble(),
                statefulMs = requireNotNull(fields["stateful"]) { "no stateful in: $line" }.toDouble(),
                browserVersion = requireNotNull(fields["browserVersion"]) { "no browserVersion in: $line" },
            )
        }.toList()

        return Campaign(
            status = requireNotNull(warmups.single()["status"]) { "no status in: ${warmups.single()}" },
            complete = ColdLane.strictBoolean(
                requireNotNull(warmups.single()["complete"]) { "no complete in: ${warmups.single()}" },
            ),
            rejecting = warmups.single()["rejecting"].orEmpty().split(',').filter { it.isNotBlank() },
            identical = ColdLane.strictBoolean(
                requireNotNull(agreements.single()["identical"]) { "no identical in: ${agreements.single()}" },
            ),
            gaps = requireNotNull(agreements.single()["gaps"]) { "no gaps in: ${agreements.single()}" }.toInt(),
            observations = observations,
        )
    }

    private data class Observation(
        val statelessMs: Double,
        val statefulMs: Double,
        val browserVersion: String,
    )

    private data class Campaign(
        val status: String,
        val complete: Boolean,
        val rejecting: List<String>,
        val identical: Boolean,
        val gaps: Int,
        val observations: List<Observation>,
    )

    private companion object {
        /** The same ceiling the JVM and Node arms assert, and for the same BASIS rather than by inheritance. */
        const val KEYSTROKE_CEILING = 1.0
        const val SAMPLER = "build/browser-scripts/after-edit.js"
        const val CHILD_TIMEOUT_SECONDS = 1_800L
    }
}
