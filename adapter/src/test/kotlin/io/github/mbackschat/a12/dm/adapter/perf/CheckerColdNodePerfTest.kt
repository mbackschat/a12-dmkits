package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.ceil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

/**
 * H5-f: the cold boundary on **plain Node**, against the package a consumer installs.
 *
 * **Why this lane exists is a denominator, not a new idea.** §8's cold row asks for "installed package load
 * through preparation and first complete consistency result, **on JVM, plain Node and browser**" — three named
 * hosts. Every H5 lane built before 2026-09-17 lives in this module and therefore on the JVM, so "measured" and
 * "measured on the JVM" were indistinguishable across the whole set, and row 8 could record that "every budget
 * H5's required list names is now set" while naming only the five QUANTITIES. That is the same mistake the row
 * had already confessed one axis over — counting boundaries rather than required observations is what let the
 * resources row read as done on one of its four. The host axis was the constant nobody had varied.
 *
 * **And the JVM arm does not cover this host even in part, for a second reason: it never used an installed
 * package.** [CheckerColdStartPerfTest] resolves `:consistency` as a project dependency on a classpath the build
 * assembles. This arm imports `a12-consistency` by package name out of a consumer project's `node_modules`,
 * populated by `npm install` from the packed tarball — so a packaging defect that leaves the published bundle
 * unloadable is observable here and provably was not there.
 *
 * **It asserts the ratified GROWTH ratio and no latency**, which is the posture the owner ratified on 2026-09-16
 * for both timing boundaries
 * ([the record](../../../../../../../../../../../docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification)).
 * A cold millisecond figure on a JS engine is machine-dependent and has no certified basis, and asserting one
 * would also breach H5's ordering rule — the bar would be fitted to this lane's own first run.
 * `PerfBounds.SUB_QUADRATIC_MAX` escapes that because it was ratified as a mechanism long before this host had a
 * lane, so the medians below stay informative while the ratio between them gates. No anchor is written: an
 * anchor IS a bar, and this runtime has no anchored history.
 *
 * **The runtime is PINNED and the lane refuses rather than measures.** [NodeIdentity] carries the reason; the
 * short version is that the estate's Node discovery returned whatever was newest, which is not a pin, and
 * H5 takes every budget through IF137's six points of which (2) is a pinned runtime.
 *
 * **Three preflights, because a timing over the wrong thing is worse than no timing.** The checker must ACCEPT
 * the synthesized model at both scales, or these processes timed the rejection path. The consumer's
 * `node_modules` must contain the checker and nothing else, which is the closure claim the delivery row makes,
 * checked here at the moment a second package would silently inflate a load time. And every child must report
 * the same runtime, since a task misconfigured to launch two would otherwise pass the pin once and publish a
 * ratio measured across two engines.
 *
 * **Isolation is the caller's job and this lane enforces the half it can.** The owning Gradle task is
 * single-fork and never cacheable; that no other build, demo or eval runs beside it is a machine condition no
 * code can check.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.coldnode", matches = "true")
class CheckerColdNodePerfTest {

    @Test
    fun theColdNodeCheckerGrowsBelowTheCatastropheFloorOverIndependentProcesses(@TempDir work: Path) {
        val node = Path.of(requiredProperty("perf.checker.coldnode.node"))
        val consumer = Path.of(requiredProperty("perf.checker.coldnode.project"))
        val processes = requiredProperty("perf.checker.coldnode.processes").toInt()
        val programs = requiredProperty("perf.checker.coldnode.n").toInt()
        NodeIdentity.requireSupported(probeVersion(node), requiredProperty("perf.checker.coldnode.major"))
        require(processes >= MINIMUM_PROCESSES) {
            "the cold boundary asks for at least $MINIMUM_PROCESSES independent processes per scale, not $processes"
        }
        // The ratified estimator medians the within-pair ratios, which needs an odd count so that the median
        // names a ratio actually observed rather than the midpoint of two that were not.
        require(processes % 2 == 1) {
            "the paired estimator medians the within-pair ratios, so the process count must be odd, not $processes"
        }
        require(programs > 0) { "perf.checker.coldnode.n must be positive" }
        requireInstalledCheckerOnly(consumer)

        val scales = listOf(programs, programs * 4)
        val models = scales.associateWith { scale ->
            val model = work.resolve("synth-$scale.dm.json")
            // The SAME generator the JVM arms use, so the two hosts are compared on one workload rather than on
            // two that merely have the same name. Synthesis needs the typed kits and so has to happen here;
            // measurement does not, which is why only the model TEXT crosses into the Node process.
            Files.writeString(model, ProfileSynthesisTargetProfiles.modelJson(scale))
            model
        }

        PerfHarness.assertGrowthBelowCatastropheFloor(
            "portable checker COLD N/4N over model declarations, plain Node",
            "the same whole-model declaration walk the JVM lanes guard, in the mode an npm consumer gets — a " +
                "fresh Node process that has never loaded the package",
        ) {
            pairedColdGrowth(node, consumer, scales, models, processes)
        }
    }

    /**
     * One estimate: [processes] interleaved N/4N pairs of fresh Node processes, the median of the within-pair
     * ratios.
     *
     * Interleaving is what makes this the ratified estimator rather than two campaigns, and it costs nothing —
     * the same `2 × processes` children were already being launched. Both members of a pair meet the same
     * machine-load slice, so drift over a multi-minute run cannot be read as growth.
     */
    private fun pairedColdGrowth(
        node: Path,
        consumer: Path,
        scales: List<Int>,
        models: Map<Int, Path>,
        processes: Int,
    ): Growth {
        val observed = scales.associateWith { mutableListOf<ColdRun>() }
        return PerfHarness.pairedGrowth(scales.first(), scales.last(), processes) { scale ->
            val single = run(node, consumer, models.getValue(scale), "synth-$scale")
            observed.getValue(scale) += single
            single.checkNanos / 1_000_000.0
        }.also { growth ->
            observed.forEach { (scale, runs) ->
                val refused = runs.map { it.rejecting }.distinct().filter { it.isNotEmpty() }
                check(refused.isEmpty()) {
                    "the checker REFUSED the synthesized model at $scale programs, so these processes timed the " +
                        "rejection path rather than a complete report: $refused"
                }
                // An empty rejecting set is ALSO what a report with no findings at all looks like, so the
                // shape is pinned beside it — the same pair the browser arm asserts, for the same reason.
                val shapes = runs.map { it.status to it.complete }.distinct()
                check(shapes == listOf(EXPECTED_SHAPE)) {
                    "the children did not all return the expected report shape at $scale programs, so this " +
                        "quantity is not the one the other cold arms measure: $shapes"
                }
                report("check", scale, runs.map { it.checkNanos })
                report("process", scale, runs.map { it.processNanos })
            }
            // The version every child actually reported, not the one the pin was checked against once: a task
            // misconfigured to launch two different runtimes would otherwise pass the pin and publish a ratio
            // measured across both.
            val versions = observed.values.flatten().map { it.nodeVersion }.distinct().sorted()
            check(versions.size == 1) {
                "the children ran on more than one Node runtime, so this ratio spans two engines: $versions"
            }
            PerfHarness.progress(
                "[checker-cold-node] runtime=${versions.single()} " +
                    "paired programs=${scales.first()}->${scales.last()} " +
                    "median=${format(growth.smallValue)}->${format(growth.largeValue)} ms " +
                    "paired-ratio=${format(growth.ratio)}x",
            )
        }
    }

    /**
     * Nearest-rank p95 with the median beside it, the pair the cold boundary asks for.
     *
     * The median says what the boundary usually costs and the p95 says what a user occasionally waits for; a
     * cold lane spread over independent processes has a long right tail by construction, so either number alone
     * describes a different machine. Nearest rank needs no interpolation and therefore always names an
     * observation that actually happened.
     */
    private fun report(quantity: String, programs: Int, nanos: List<Long>) {
        val sorted = nanos.sorted()
        val p95 = sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
        PerfHarness.progress(
            "[checker-cold-node] $quantity programs=$programs n=${sorted.size} " +
                "median=${millis(sorted[sorted.size / 2])} p95=${millis(p95)} " +
                "min=${millis(sorted.first())} max=${millis(sorted.last())} ms",
        )
    }

    /** One child: a Node process whose whole program is [COLD_SCRIPT], run from the installed consumer tree. */
    private fun run(node: Path, consumer: Path, model: Path, label: String): ColdRun {
        val completed = ChildProcess.runWithFuse(
            command = listOf(node.toString(), COLD_SCRIPT, model.toString(), label),
            // The working directory is what makes `import 'a12-consistency'` resolve to the INSTALLED package
            // rather than to anything this build compiled: Node walks up from the importing file's directory.
            directory = consumer,
            timeoutSeconds = PROCESS_TIMEOUT_SECONDS,
        ) { "a cold Node process" }
        val output = completed.output
        check(completed.exitCode == 0) { "a cold Node process exited ${completed.exitCode}: $output" }
        val printed = output.lineSequence()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { (key, value) -> key to value }
        return ColdRun(
            checkNanos = requireNotNull(printed["checkNanos"]) { "no checkNanos in: $output" }.toLong(),
            processNanos = requireNotNull(printed["processNanos"]) { "no processNanos in: $output" }.toLong(),
            rejecting = printed["rejecting"].orEmpty().split(',').filter { it.isNotBlank() },
            status = requireNotNull(printed["status"]) { "no status in: $output" },
            complete = strictBoolean(requireNotNull(printed["complete"]) { "no complete in: $output" }),
            nodeVersion = requireNotNull(printed["nodeVersion"]) { "no nodeVersion in: $output" },
        )
    }

    /**
     * The enforcement locus for "the timed process loads the installed checker, and only it".
     *
     * Two claims, and the second is the delivery row's own: the checker's `package.json` declares no
     * dependencies, so the tarball is the whole production closure — which means a populated `node_modules`
     * holding anything besides `a12-consistency` is either a closure the delivery figure does not describe or a
     * consumer project that grew a dependency, and both make this load time an answer to a different question.
     * Every DOT-prefixed entry is skipped, files and directories alike — `.package-lock.json` and the `.bin`
     * directory npm creates for executables are its own bookkeeping, not installed packages. A scoped package
     * would surface as its `@scope` directory and duly fail, since that is not dot-prefixed.
     */
    private fun requireInstalledCheckerOnly(consumer: Path) {
        val script = consumer.resolve(COLD_SCRIPT)
        check(Files.isRegularFile(script)) {
            "the staged consumer project carries no $COLD_SCRIPT, so there is nothing to launch: $consumer"
        }
        val modules = consumer.resolve("node_modules")
        check(Files.isDirectory(modules.resolve(CHECKER_PACKAGE))) {
            "the consumer project has no installed $CHECKER_PACKAGE, so this lane would measure a Node process " +
                "that cannot check anything: $modules"
        }
        val installed = Files.list(modules).use { entries ->
            entries.map { it.fileName.toString() }
                .filter { !it.startsWith('.') }
                .sorted()
                .toList()
        }
        check(installed == listOf(CHECKER_PACKAGE)) {
            "the consumer project's node_modules carries packages besides the checker, so the load this lane " +
                "times is not the published closure the delivery row measured: $installed"
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

    private fun millis(nanos: Long): String = format(nanos / 1_000_000.0)

    private fun format(value: Double): String = "%.2f".format(Locale.ROOT, value)

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-D$name=<value> is required" }

    /**
     * A boolean that refuses anything else, because `String.toBoolean()` would not.
     *
     * Kotlin maps every string but a case-insensitive `"true"` to `false`, and `false` is the value this lane
     * EXPECTS for `complete` — so `undefined`, a renamed field or any garbage would land on the expected side
     * and pass silently. `requireNotNull` covers an absent key; only this covers a meaningless value.
     */
    private fun strictBoolean(raw: String): Boolean = when (raw) {
        "true" -> true
        "false" -> false
        else -> error("a cold Node child printed complete=$raw, which is not a boolean")
    }

    private data class ColdRun(
        val checkNanos: Long,
        val processNanos: Long,
        val rejecting: List<String>,
        val status: String,
        val complete: Boolean,
        val nodeVersion: String,
    )

    private companion object {
        /** The cold boundary's own floor on the sample count; a smaller run is a different measurement. */
        const val MINIMUM_PROCESSES = 50

        /**
         * The report the synthesized workload draws, asserted rather than assumed.
         *
         * `INCONCLUSIVE` and incomplete because the checker does not yet implement every family the model
         * exercises — the point is not which shape it is but that it is the SAME one on every host, so the
         * three cold arms are timing one quantity. The browser arm pins the identical pair.
         */
        val EXPECTED_SHAPE = "INCONCLUSIVE" to false

        /** Liveness fuse for a stuck child, not a latency bar. */
        const val PROCESS_TIMEOUT_SECONDS = 120L

        /** A `--version` probe that hangs is a broken toolchain, so it gets a short fuse rather than the long one. */
        const val PROBE_TIMEOUT_SECONDS = 20L

        const val COLD_SCRIPT = "cold-first-result.mjs"

        const val CHECKER_PACKAGE = "a12-consistency"
    }
}
