package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5-h's BROWSER arm: the checker's prepared growth in one warm browsing context, the third host §8's prepared
 * row reaches once the owner-confirmed host axis is read over the whole budget list.
 *
 * **One warm context runs the entire campaign, which is the opposite of what the cold arm does** and the
 * difference that makes this a separate page rather than a parameter. [CheckerColdBrowserPerfTest] takes a
 * fresh context per observation because an empty module registry is where its claim lives; here the module is
 * loaded and the checker warmed before any clock starts, because §8 asks for the two costs "reported
 * separately" and a fresh context would re-measure exactly what this boundary excludes.
 *
 * **The quantity is the one [CheckerGrowthPerfTest] clocks**, established by reading that lane rather than
 * assumed: a repeated whole-model `check` on a warmed runtime, not a snapshot-reuse call. So `checkModelJson`
 * after a per-scale warm-up is the analogue, and the checker's published two-function JS surface suffices — a
 * prepared entry point is deliberately unexported, and timing one through an unexported Kotlin function would
 * report a cost no browser consumer can obtain.
 *
 * **The bar is the already-ratified growth ratio and nothing else.** `PerfBounds.SUB_QUADRATIC_MAX` was
 * ratified as a mechanism on 2026-09-16, before either JS host had a lane. No duration is asserted and no
 * anchor written — a browser millisecond has no basis that survives another machine, another Chrome or another
 * bundler. Asserting the ratio on a fourth carrier is a scope transfer, so it rests on this arm's own
 * measurement rather than on the Node arm's.
 *
 * **Chrome is recorded, not pinned.** The project uses the system browser deliberately and no build file can
 * pin one, so this arm closes H5's pinned-runtime point by attribution: every observation carries the build it
 * ran on and the lane refuses a set that carries no single non-blank build. It does NOT claim to refuse two
 * differing builds — one launch per run means no input can produce a second value, and a check that cannot
 * fail is a guard by appearance only.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.preparedbrowser", matches = "true")
class CheckerPreparedBrowserPerfTest {

    @Test
    fun thePreparedCheckerGrowsBelowTheCatastropheFloorInARealBrowser() {
        val consumer = Path.of(ColdLane.requiredProperty("perf.checker.preparedbrowser.project"))
        val pnpm = Path.of(ColdLane.requiredProperty("perf.checker.preparedbrowser.pnpm"))
        val node = Path.of(ColdLane.requiredProperty("perf.checker.preparedbrowser.node"))
        val pairs = ColdLane.requiredProperty("perf.checker.preparedbrowser.pairs").toInt()
        val programs = ColdLane.requiredProperty("perf.checker.preparedbrowser.n").toInt()
        require(pairs >= ColdLane.MINIMUM_OBSERVATIONS) {
            "the prepared boundary's floor is ${ColdLane.MINIMUM_OBSERVATIONS} observations per scale, not $pairs"
        }
        // The ratified estimator medians the within-pair ratios, so an odd count makes that median a ratio
        // actually observed rather than the midpoint of two that were not.
        require(pairs % 2 == 1) { "the paired estimator needs an odd pair count, not $pairs" }
        require(programs > 0) { "perf.checker.preparedbrowser.n must be positive" }

        val scales = listOf(programs, programs * 4)
        val served = BrowserConsumerLane.stageWorkloads(consumer, scales)
        // Outside the estimate: the bundle is part of no observation, and rebuilding it per re-measurement
        // attempt would add a minute to each for nothing.
        BrowserConsumerLane.buildBundle(consumer, pnpm, CHILD_TIMEOUT_SECONDS)

        PerfHarness.assertGrowthBelowCatastropheFloor(
            "portable checker PREPARED N/4N over model declarations, real browser",
            "the same whole-model declaration walk the JVM and Node prepared lanes guard — the shape that made " +
                "this check cubic in 2026-09 and is invisible to every correctness test",
        ) { preparedGrowth(consumer, node, scales, served, pairs) }
    }

    /**
     * One estimate: a fresh campaign, its preflights, and the median of the within-pair ratios.
     *
     * **Sampling belongs inside this function**, for the reason the cold browser arm's control established: the
     * assertion helper re-measures on a breach, so an estimate collected once outside would be drained by
     * attempt one and attempt two would pop an empty list. Re-measuring is also the only correct semantics —
     * reusing one sample set returns the identical ratio, so the re-measurement would confirm itself.
     */
    private fun preparedGrowth(
        consumer: Path,
        node: Path,
        scales: List<Int>,
        served: Map<Int, String>,
        pairs: Int,
    ): Growth {
        val visits = (1..pairs).flatMap { scales }
        val campaign = sample(consumer, node, scales, served, visits)

        check(campaign.warmups.size == scales.size) {
            "the page warmed ${campaign.warmups.size} scale(s) for ${scales.size}, so at least one observation " +
                "was taken in a context this lane never warmed"
        }
        campaign.warmups.forEach { warmup ->
            check(warmup.rejecting.isEmpty()) {
                "the checker REFUSED the synthesized model at ${warmup.scale} programs, so this campaign would " +
                    "compare the cost of rejecting rather than of checking: ${warmup.rejecting}"
            }
            check(warmup.status to warmup.complete == ColdLane.EXPECTED_WIRE_SHAPE) {
                "the page returned ${warmup.status}/${warmup.complete} at ${warmup.scale} programs rather than " +
                    "${ColdLane.EXPECTED_WIRE_SHAPE}, so this quantity is not the one the other arms measure"
            }
        }

        val browsers = campaign.observations.map { it.browserVersion }.distinct().sorted()
        check(browsers.size == 1 && browsers.single().isNotBlank()) {
            "the observations carry no single non-blank browser build, so these numbers are unattributable: " +
                browsers
        }

        val byScale = scales.associateWith { scale ->
            campaign.observations.filter { it.scale == scale }.toMutableList()
        }
        byScale.forEach { (scale, observations) ->
            // Per SCALE, not just in total: a page emitting one extra small and one missing large keeps the
            // total at `2 × pairs`, clears every preflight, and then dies on an empty pop below.
            check(observations.size == pairs) {
                "the page produced ${observations.size} observation(s) at $scale programs, not $pairs, so the " +
                    "pairs the estimator is about to reconstruct do not exist"
            }
            ColdLane.report("checker-prepared-browser", "check", scale, observations.map { it.checkMillis })
            // Reported rather than asserted, as on the Node arm: this arm's `check` includes rendering the
            // report to JSON where the JVM quantity stops at the object, so a reader comparing ratios across
            // hosts needs to know whether the report itself grows with the model.
            val chars = observations.map { it.reportChars }.distinct().sorted()
            PerfHarness.progress(
                "[checker-prepared-browser] reportChars programs=$scale distinct=${chars.size} " +
                    "min=${chars.first()} max=${chars.last()}",
            )
        }

        return PerfHarness.pairedGrowth(scales.first(), scales.last(), pairs) { scale ->
            byScale.getValue(scale).removeFirst().checkMillis
        }.also { growth ->
            PerfHarness.progress(
                // `Chrome/` prefixed, as the cold arm does: `browser.version()` returns a bare number, and a
                // bare number in a record is the kind of figure nobody can identify a year later.
                "[checker-prepared-browser] runtime=Chrome/${browsers.single()} " +
                    "paired programs=${scales.first()}->${scales.last()} " +
                    "median=${ColdLane.format(growth.smallValue)}->${ColdLane.format(growth.largeValue)} ms " +
                    "paired-ratio=${ColdLane.format(growth.ratio)}x",
            )
        }
    }

    /** Run the sampler once and read back its warm-up and observation lines, in the order it printed them. */
    private fun sample(
        consumer: Path,
        node: Path,
        scales: List<Int>,
        served: Map<Int, String>,
        visits: List<Int>,
    ): Campaign {
        val models = scales.map { scale -> "$scale@${served.getValue(scale)}" }
        val output = BrowserConsumerLane.runToCompletion(
            consumer,
            listOf(node.toString(), SAMPLER) + models + visits.joinToString(","),
            "the browser prepared sampler",
            node,
            CHILD_TIMEOUT_SECONDS,
        )

        val warmups = output.lineSequence().filter { it.startsWith("warmup ") }.map { line ->
            val fields = ColdLane.taggedFields(line, "warmup")
            Warmup(
                scale = requireNotNull(fields["scale"]) { "no scale in: $line" }.toInt(),
                status = requireNotNull(fields["status"]) { "no status in: $line" },
                complete = ColdLane.strictBoolean(requireNotNull(fields["complete"]) { "no complete in: $line" }),
                rejecting = fields["rejecting"].orEmpty().split(',').filter { it.isNotBlank() },
            )
        }.toList()

        val observations = output.lineSequence().filter { it.startsWith("observation ") }.map { line ->
            val fields = ColdLane.taggedFields(line, "observation")
            Observation(
                scale = requireNotNull(fields["scale"]) { "no scale in: $line" }.toInt(),
                checkMillis = requireNotNull(fields["checkMillis"]) { "no checkMillis in: $line" }.toDouble(),
                reportChars = requireNotNull(fields["reportChars"]) { "no reportChars in: $line" }.toInt(),
                browserVersion = requireNotNull(fields["browserVersion"]) { "no browserVersion in: $line" },
            )
        }.toList()
        check(observations.size == visits.size) {
            "the page published ${observations.size} observations for ${visits.size} requested visits: $output"
        }
        return Campaign(warmups, observations)
    }

    private data class Warmup(
        val scale: Int,
        val status: String,
        val complete: Boolean,
        val rejecting: List<String>,
    )

    private data class Observation(
        val scale: Int,
        val checkMillis: Double,
        val reportChars: Int,
        val browserVersion: String,
    )

    private data class Campaign(val warmups: List<Warmup>, val observations: List<Observation>)

    private companion object {
        /** The compiled sampler, in the consumer project's own script output directory. */
        const val SAMPLER = "build/browser-scripts/prepared.js"

        /**
         * Liveness fuse for a stuck child, not a latency bar.
         *
         * Generous because one child now covers a bundle build, a Chrome launch and the whole campaign — and
         * because it can only ever fire on a hang: [ChildProcess.runWithFuse] redirects to a file, so the
         * timeout is reachable rather than the unreachable code every lane here carried until 2026-09-17.
         */
        const val CHILD_TIMEOUT_SECONDS = 1_800L
    }
}
