package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.ceil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5-g: the cold boundary in a real **browser** — the third and last host §8's cold row names.
 *
 * With the plain-Node arm (H5-f) this closes that row's host grid. What a browser adds over Node is not the
 * engine, which is V8 in both, but everything around it: a **bundler's** output instead of Node's own ESM
 * graph, an **HTTP transfer** instead of a filesystem read, and a **document** context. None of that is
 * observable from the other two arms, and the transfer in particular turns the delivery row's byte figure into
 * a number a consumer actually waits for.
 *
 * **The runtime these numbers describe is CHROME, and this arm closes H5's pinned-runtime point by ATTRIBUTION
 * rather than by a pin.** The other two arms pin theirs — [JdkIdentity] the JVM, [NodeIdentity] the Node major —
 * but the browser here is the SYSTEM browser on purpose, which no build file can pin, so instead every
 * observation carries the build it ran on and the lane refuses a set that carries no single non-blank build.
 * That keeps a future Chrome update from silently re-basing the published figures with nothing in the record
 * naming which build produced them. What it deliberately does NOT claim is a refusal of two differing builds:
 * the sampler launches Chrome once, so no input can produce a second value and such a check could not fail.
 * The `node` that drives Playwright is pinned all the same, and is not the runtime under observation.
 *
 * **The measurement is taken in a fresh browsing CONTEXT per observation, not a fresh Chrome.** A new context
 * starts with an empty module registry and an empty HTTP cache, which is where the cold claim lives; relaunching
 * the browser a hundred times would add its bootstrap to every sample and measure Chrome instead of the checker.
 * What a fresh context does not reset is written on the page rather than claimed away here.
 *
 * **The sampler owns no statistic and this lane owns no browser.** `scripts/cold.ts` visits the models in the
 * order this lane asks for and prints one line per observation; the grouping, the medians and the ratified
 * paired estimator stay in [PerfHarness], where every other lane's are. A second implementation of a ratified
 * estimator inside a TypeScript driver would be free to drift from it with nothing to notice.
 *
 * **`moduleMillis` is reported separately because it is also a build CHECK.** The cold page imports the checker
 * dynamically so that Rollup emits it as its own chunk and the load lands inside the timed region; a build that
 * inlined the chunk into the entry would make the page measure a warm call, and the tell is a near-zero module
 * load. So the lane refuses a module load it cannot distinguish from zero rather than publishing a figure whose
 * meaning silently changed.
 *
 * **Why this lane orchestrates the browser build instead of `:consistency` doing it.** The workload is produced
 * by the typed synthesizer, which lives here, and `:adapter` already depends on `:consistency` — so a
 * `:consistency` task consuming this module's output would close a cycle. The models are therefore written into
 * the staged consumer's `public/` tree by this lane, before it drives the bundle build that copies them.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.coldbrowser", matches = "true")
class CheckerColdBrowserPerfTest {

    @Test
    fun theColdBrowserCheckerGrowsBelowTheCatastropheFloorOverIndependentContexts() {
        val consumer = Path.of(requiredProperty("perf.checker.coldbrowser.project"))
        val pnpm = Path.of(requiredProperty("perf.checker.coldbrowser.pnpm"))
        val node = Path.of(requiredProperty("perf.checker.coldbrowser.node"))
        val pairs = requiredProperty("perf.checker.coldbrowser.pairs").toInt()
        val programs = requiredProperty("perf.checker.coldbrowser.n").toInt()
        require(pairs >= MINIMUM_PAIRS) {
            "the cold boundary asks for at least $MINIMUM_PAIRS independent observations per scale, not $pairs"
        }
        // The ratified estimator medians the within-pair ratios, so an odd count makes that median a ratio
        // actually observed rather than the midpoint of two that were not.
        require(pairs % 2 == 1) { "the paired estimator needs an odd pair count, not $pairs" }
        require(programs > 0) { "perf.checker.coldbrowser.n must be positive" }

        val scales = listOf(programs, programs * 4)
        val served = stageWorkloads(consumer, scales)
        // The bundle build is OUTSIDE the estimate: it is not part of any observation, and rebuilding it per
        // attempt would only add a minute to a re-measurement.
        buildBundle(consumer, pnpm)

        PerfHarness.assertGrowthBelowCatastropheFloor(
            "portable checker COLD N/4N over model declarations, real browser",
            "the same whole-model declaration walk the JVM and Node lanes guard, in the mode a browser consumer " +
                "gets — a fresh browsing context that has never fetched the checker's chunk",
        ) {
            pairedBrowserGrowth(consumer, node, scales, served, pairs)
        }
    }

    /**
     * One estimate: fresh samples, the preflights over them, and the median of the within-pair ratios.
     *
     * **Sampling belongs INSIDE this function, and the control is what established that.** The assertion helper
     * RE-MEASURES on a breach, precisely so that a slow machine slice can be told apart from an algorithmic
     * blowup — "load inflates one estimate and does not reproduce". A first version collected the samples once
     * outside and let each attempt pop from per-scale queues; the queues were drained by attempt one, so the
     * lane died with a bare `NoSuchElementException` the first time growth ever breached, which is exactly when
     * it is needed. Reusing one sample set would have been worse than the crash: every retry would return the
     * identical ratio, so the re-measurement would confirm itself and the helper's whole discrimination would
     * be gone. A re-measurement has to re-measure.
     */
    private fun pairedBrowserGrowth(
        consumer: Path,
        node: Path,
        scales: List<Int>,
        served: Map<Int, String>,
        pairs: Int,
    ): Growth {
        // Interleaved visit order, which is what makes the pairs pairs: both members of one meet the same
        // machine-load slice, so drift over a multi-minute run cannot be read as growth.
        val visits = (0 until pairs).flatMap { scales.map { scale -> served.getValue(scale) } }
        val samples = sample(consumer, node, visits)
        check(samples.size == visits.size) {
            "the sampler printed ${samples.size} observation(s) for ${visits.size} requested visit(s)"
        }
        val byScale = scales.associateWith { scale ->
            samples.filter { it.model == served.getValue(scale) }.toMutableList()
        }
        byScale.forEach { (scale, observations) ->
            val refused = observations.map { it.rejecting }.distinct().filter { it.isNotEmpty() }
            check(refused.isEmpty()) {
                "the checker REFUSED the synthesized model at $scale programs in the browser, so these contexts " +
                    "timed the rejection path rather than a complete report: $refused"
            }
            // The report's own shape, asserted rather than assumed: the checker cannot say VALID, so an
            // INCONCLUSIVE-and-incomplete verdict is what a complete run over a silent model looks like. A
            // page that published a different shape measured something else.
            val shapes = observations.map { it.status to it.complete }.distinct()
            check(shapes == listOf("INCONCLUSIVE" to false)) {
                "the browser reported $shapes at $scale programs, not one INCONCLUSIVE/incomplete shape"
            }
            val inlined = observations.count { it.moduleMillis < MINIMUM_MODULE_MILLIS }
            check(inlined == 0) {
                "$inlined of ${observations.size} context(s) at $scale programs loaded the checker chunk in " +
                    "under $MINIMUM_MODULE_MILLIS ms, which is what an INLINED chunk looks like — the page would " +
                    "then be timing a warm call, so the cold claim would be false rather than slow"
            }
            // Per-scale, not just the total: a sampler emitting one extra small and one missing large keeps the
            // total at `2 × pairs`, clears every preflight, and then dies on `removeFirst()` below with the bare
            // `NoSuchElementException` this lane has already been fixed for once. The total cannot see that.
            check(observations.size == pairs) {
                "the sampler produced ${observations.size} observation(s) at $scale programs, not $pairs, so the " +
                    "pairs the estimator is about to reconstruct do not exist"
            }
            report("check", scale, observations.map { it.checkMillis })
            report("module", scale, observations.map { it.moduleMillis })
        }
        // The browser build these numbers describe, checked for PRESENCE rather than for multiplicity.
        //
        // An earlier version asserted `browsers.size == 1` and three surfaces advertised it as refusing "a set
        // spanning two". It could not fail: the sampler launches Chrome ONCE and reads `browser.version()` once,
        // so every line carries the same constant and no input can produce a second value. That is a guard by
        // appearance only, and the Node arm's identical-looking check is honest solely because each of ITS
        // children is a separate process that could resolve a different binary — the justification was carried
        // across a carrier boundary where it stops being true. What can actually go wrong here is the version
        // going missing, so that is what is checked.
        val browsers = samples.map { it.browserVersion }.distinct().sorted()
        check(browsers.size == 1 && browsers.single().isNotBlank()) {
            "the observations carry no single non-blank browser build, so these numbers are unattributable: " +
                browsers
        }
        // Popped in FIFO order per scale, which reconstructs the pairs the sampler visited: the harness asks
        // for small then large, and the sampler was given exactly that order.
        return PerfHarness.pairedGrowth(scales.first(), scales.last(), pairs) { scale ->
            byScale.getValue(scale).removeFirst().checkMillis
        }.also { growth ->
            PerfHarness.progress(
                "[checker-cold-browser] runtime=Chrome/${browsers.single()} " +
                    "paired programs=${scales.first()}->${scales.last()} " +
                    "median=${format(growth.smallValue)}->${format(growth.largeValue)} ms " +
                    "paired-ratio=${format(growth.ratio)}x",
            )
        }
    }

    /**
     * Write the synthesized models where Vite will serve them, and return the URL each scale is served at.
     *
     * `public/` is copied to the site root by the bundle build, so these become `/perf/synth-<n>.dm.json`. The
     * models come from the SAME generator the JVM and Node arms use, so the three hosts are compared on one
     * workload rather than on three that share a name.
     */
    private fun stageWorkloads(consumer: Path, scales: List<Int>): Map<Int, String> {
        val servedDir = consumer.resolve("public").resolve("perf")
        Files.createDirectories(servedDir)
        return scales.associateWith { scale ->
            val name = "synth-$scale.dm.json"
            Files.writeString(servedDir.resolve(name), ProfileSynthesisTargetProfiles.modelJson(scale))
            "perf/$name"
        }
    }

    /** Build the bundle and the sampler, through the consumer project's own scripts. */
    private fun buildBundle(consumer: Path, pnpm: Path) {
        runToCompletion(consumer, listOf(pnpm.toString(), "run", "build"), "the browser bundle build", pnpm)
        runToCompletion(consumer, listOf(pnpm.toString(), "run", "build:scripts"), "the sampler build", pnpm)
    }

    /** Run the sampler once, and parse its observation lines in the order it printed them. */
    private fun sample(consumer: Path, node: Path, visits: List<String>): List<ColdObservation> {
        val output = runToCompletion(
            consumer,
            listOf(node.toString(), SAMPLER) + visits,
            "the browser cold sampler",
            node,
        )
        return output.lineSequence()
            .filter { it.startsWith("observation ") }
            .map { line ->
                val fields = line.removePrefix("observation ").split(' ')
                    .mapNotNull { field -> field.split('=', limit = 2).takeIf { it.size == 2 } }
                    .associate { (key, value) -> key to value }
                ColdObservation(
                    model = requireNotNull(fields["model"]) { "no model in: $line" },
                    moduleMillis = requireNotNull(fields["moduleMillis"]) { "no moduleMillis in: $line" }.toDouble(),
                    checkMillis = requireNotNull(fields["checkMillis"]) { "no checkMillis in: $line" }.toDouble(),
                    status = requireNotNull(fields["status"]) { "no status in: $line" },
                    complete = strictBoolean(requireNotNull(fields["complete"]) { "no complete in: $line" }),
                    browserVersion = requireNotNull(fields["browserVersion"]) { "no browserVersion in: $line" },
                    rejecting = fields["rejecting"].orEmpty().split(',').filter { it.isNotBlank() },
                )
            }
            .toList()
    }

    /**
     * One child, with the resolved tool's directory prepended to `PATH`.
     *
     * pnpm's launcher is itself a node script, so a child that cannot find `node` fails in a way that reads as a
     * project error rather than a toolchain one — which is why the directory goes on `PATH` rather than only the
     * executable being named.
     */
    private fun runToCompletion(
        workingDir: Path,
        command: List<String>,
        what: String,
        tool: Path,
    ): String {
        val toolDir = requireNotNull(tool.parent) { "$tool has no parent directory" }
        val completed = ChildProcess.runWithFuse(
            command = command,
            directory = workingDir,
            environment = mapOf("PATH" to "$toolDir${java.io.File.pathSeparator}${System.getenv("PATH")}"),
            timeoutSeconds = CHILD_TIMEOUT_SECONDS,
        ) { what }
        check(completed.exitCode == 0) { "$what exited ${completed.exitCode}: ${completed.output}" }
        return completed.output
    }

    /**
     * Nearest-rank p95 with the median beside it, the pair the cold boundary asks for.
     *
     * A cold lane over independent contexts has a long right tail by construction, so either number alone
     * describes a different machine. Nearest rank needs no interpolation and so always names an observation that
     * actually happened.
     */
    private fun report(quantity: String, programs: Int, millis: List<Double>) {
        val sorted = millis.sorted()
        val p95 = sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
        PerfHarness.progress(
            "[checker-cold-browser] $quantity programs=$programs n=${sorted.size} " +
                "median=${format(sorted[sorted.size / 2])} p95=${format(p95)} " +
                "min=${format(sorted.first())} max=${format(sorted.last())} ms",
        )
    }

    /**
     * A boolean that refuses anything else, because `String.toBoolean()` would not.
     *
     * Kotlin maps every string but a case-insensitive `"true"` to `false` — and `false` is exactly the value
     * this lane EXPECTS for `complete`, so `undefined`, a renamed field or any garbage would land on the
     * expected side and pass. `requireNotNull` covers an absent key; only this covers a meaningless value.
     */
    private fun strictBoolean(raw: String): Boolean = when (raw) {
        "true" -> true
        "false" -> false
        else -> error("the sampler printed complete=$raw, which is not a boolean")
    }

    private fun format(value: Double): String = "%.2f".format(Locale.ROOT, value)

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-D$name=<value> is required" }

    private data class ColdObservation(
        val model: String,
        val moduleMillis: Double,
        val checkMillis: Double,
        val status: String,
        val complete: Boolean,
        val browserVersion: String,
        val rejecting: List<String>,
    )

    private companion object {
        /**
         * The smallest odd pair count that clears the cold boundary's floor of **50** observations per scale.
         *
         * The floor is 50, as in both other cold lanes; 51 rather than 50 because the ratified estimator medians
         * the within-pair ratios and an even count would make that median the midpoint of two ratios nobody
         * observed.
         */
        const val MINIMUM_PAIRS = 51

        /** A bundle build, a tsc pass and a hundred navigations all live under this; a liveness fuse, not a bar. */
        const val CHILD_TIMEOUT_SECONDS = 1_800L

        /**
         * Below this, a "module load" is indistinguishable from an already-evaluated chunk.
         *
         * It is a build assertion rather than a performance one: fetching a chunk over loopback and evaluating it
         * cannot plausibly complete in under a millisecond, so a figure that small means the dynamic import
         * resolved from the entry chunk and the page timed a warm call.
         */
        const val MINIMUM_MODULE_MILLIS = 1.0

        const val SAMPLER = "build/browser-scripts/cold.js"
    }
}
