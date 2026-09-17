package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

/**
 * H5-b: what the Kernel-free checker costs to reach its FIRST result, measured on processes that have never
 * checked anything.
 *
 * **It asserts the ratified GROWTH ratio and no latency — the split the owner ratified on 2026-09-16**
 * ([the record](../../../../../../../../../../../docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification)).
 * A cold millisecond figure is machine-dependent and has no certified basis, so no duration is asserted and no
 * anchor is written: an anchor IS a bar. A ratio is a different quantity — `PerfBounds.SUB_QUADRATIC_MAX` was
 * ratified as a mechanism long before this module existed — so the medians below stay informative while the
 * ratio between them gates.
 *
 * **The cold ratio is the LOOSER of the two growth instruments, which is stated rather than hidden.** This lane
 * and [CheckerGrowthPerfTest] clock the same operation, model text to complete report, and the fixed ~130 ms of
 * class loading and serializer setup that only this one pays *deflates* its ratio — so a blowup trips the
 * prepared lane first. It is asserted anyway for two reasons that are not detection: the owner ratified the
 * ratio for both boundaries, and this is the mode a standalone consumer actually runs in, where nothing else in
 * the estate bars growth at all. No claim is made that it sees a class the prepared lane cannot.
 *
 * **What it does assert is the preflight, and the report rather than a count.** A timing over a model the
 * checker refuses would be timing the rejection path, so every process prints its rejecting findings and the
 * expected set — empty at both scales, the claim
 * [CheckerSilenceOnSynthesizedModelsTest] holds on the same synthesized shapes — is compared whole.
 *
 * **And it asserts the classpath, because "cold standalone checking" is a claim about what gets loaded.** The
 * timed process resolves `:consistency` and its declared dependencies alone; a kernel artifact on that path
 * would make every number here an answer to a question H5 does not ask. The one directory on it is this
 * module's own test output, which carries [CheckerColdStartMain] and nothing that entry point references.
 *
 * **Isolation is the caller's job and the lane enforces half of it.** [JdkIdentity.requireSupported] refuses to
 * record on anything but the pinned OpenJDK, and the owning Gradle task is single-fork and never cacheable. The
 * other half — no other build, demo or eval running beside it — is a machine condition no code can check.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.cold", matches = "true")
class CheckerColdStartPerfTest {

    @Test
    fun theColdCheckerGrowsBelowTheCatastropheFloorOverIndependentProcesses(@TempDir work: Path) {
        JdkIdentity.requireSupported()
        val classpath = requiredProperty("perf.checker.cold.classpath")
        val processes = requiredProperty("perf.checker.cold.processes").toInt()
        val programs = requiredProperty("perf.checker.cold.n").toInt()
        // A recorder attached to the child makes the run a DIAGNOSTIC rather than a measurement: sampling
        // perturbs exactly the durations this lane exists to report, so the sample floor stops applying and the
        // printed numbers stop being observations. Saying so here is what keeps a profiling run's output from
        // being read as a row for the results page — the locus question H5-b's first run raised is answered in
        // this mode, and never in the same breath as a latency.
        val recording = System.getProperty("perf.checker.cold.jfr").orEmpty()
        val diagnostic = recording.isNotBlank()
        require(diagnostic || processes >= MINIMUM_PROCESSES) {
            "H5-b asks for at least $MINIMUM_PROCESSES independent processes per scale, not $processes"
        }
        // One child per scale per pair, so the process count IS the pair count — and the ratified estimator
        // medians the within-pair ratios, which needs an odd count so that the median names a ratio actually
        // observed rather than the midpoint of two that were not.
        require(processes % 2 == 1) {
            "the paired estimator medians the within-pair ratios, so the process count must be odd, not $processes"
        }
        require(programs > 0) { "perf.checker.cold.n must be positive" }
        requireCheckerOnly(classpath)
        if (diagnostic) {
            Files.createDirectories(Path.of(recording))
            PerfHarness.progress(
                "[checker-cold] DIAGNOSTIC RUN — a flight recorder is attached to every child, so the durations " +
                    "below are perturbed, are NOT observations, and the growth floor is NOT asserted. " +
                    "Recordings: $recording",
            )
        }

        val scales = listOf(programs, programs * 4)
        val models = scales.associateWith { scale ->
            val model = work.resolve("synth-$scale.dm.json")
            Files.writeString(model, ProfileSynthesisTargetProfiles.modelJson(scale))
            if (diagnostic) {
                Files.copy(model, Path.of(recording).resolve(model.fileName.toString()))
            }
            model
        }

        // A perturbed run must not decide a bar: a recorder changes the very durations the ratio divides, so a
        // diagnostic run reports its curve and asserts nothing. Every ordinary run asserts.
        if (diagnostic) {
            pairedColdGrowth(classpath, scales, models, processes)
        } else {
            PerfHarness.assertGrowthBelowCatastropheFloor(
                "portable checker COLD N/4N over model declarations",
                "the same whole-model declaration walk the prepared lane guards, in the mode a standalone " +
                    "consumer gets — a fresh JVM that has never checked anything",
            ) {
                pairedColdGrowth(classpath, scales, models, processes)
            }
        }
    }

    /**
     * One estimate: [processes] interleaved N/4N pairs of fresh processes, the median of the within-pair ratios.
     *
     * **The interleaving is what makes this the ratified estimator rather than two separate campaigns, and it
     * costs nothing** — the same `2 × processes` children were already being launched, one scale after the
     * other. Both members of a pair now meet the same machine-load slice, so drift over a multi-minute run can
     * no longer be read as growth.
     *
     * **The per-scale median and p95 are reported from THIS estimate's own children, which is why the
     * collection lives here rather than in the caller.** A breach is re-measured, and a report that accumulated
     * across attempts would describe no single run.
     */
    private fun pairedColdGrowth(
        classpath: String,
        scales: List<Int>,
        models: Map<Int, Path>,
        processes: Int,
    ): Growth {
        val observed = scales.associateWith { mutableListOf<ColdRun>() }
        return PerfHarness.pairedGrowth(scales.first(), scales.last(), processes) { scale ->
            val single = run(classpath, models.getValue(scale), "synth-$scale")
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
                // shape is pinned beside it — the same pair both JS arms assert, for the same reason.
                val shapes = runs.map { it.status to it.complete }.distinct()
                check(shapes == listOf(ColdLane.EXPECTED_OBJECT_SHAPE)) {
                    "the children did not all return the expected report shape at $scale programs, so this " +
                        "quantity is not the one the other cold arms measure: $shapes"
                }
                report("check", scale, runs.map { it.checkNanos })
                report("process", scale, runs.map { it.processNanos })
            }
            PerfHarness.progress(
                "[checker-cold] paired programs=${scales.first()}->${scales.last()} " +
                    "median=${format(growth.smallValue)}->${format(growth.largeValue)} ms " +
                    "paired-ratio=${format(growth.ratio)}x",
            )
        }
    }

    private fun report(quantity: String, programs: Int, nanos: List<Long>) =
        ColdLane.reportNanos("checker-cold", quantity, programs, nanos)

    private fun format(value: Double): String = ColdLane.format(value)

    /**
     * One child, launched with the pinned JVM and nothing else configured — except an optional `-Xmx`.
     *
     * The heap knob exists because the first run of this lane could not tell two mechanisms apart: at 800
     * programs the spread was 4.4–10.0 s around a 8.6 s median, which is the signature of memory pressure just
     * as much as of a super-linear algorithm. Being able to re-measure one scale under a different heap is what
     * makes that a question with an answer rather than a hypothesis in a results table. Unset by default, so the
     * ordinary observation is of a standalone JVM as a user would get one.
     */
    private fun run(classpath: String, model: Path, label: String): ColdRun {
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val heap = System.getProperty("perf.checker.cold.heap").orEmpty()
        val recording = System.getProperty("perf.checker.cold.jfr").orEmpty()
        val completed = ChildProcess.runWithFuse(
            command = listOfNotNull(
                java.toString(),
                heap.takeIf { it.isNotBlank() }?.let { "-Xmx$it" },
                recording.takeIf { it.isNotBlank() }?.let {
                    "-XX:StartFlightRecording=settings=profile,dumponexit=true," +
                        "filename=$it/$label-${++recordings}.jfr"
                },
                "-cp", classpath,
                "io.github.mbackschat.a12.dm.adapter.perf.CheckerColdStartMain",
                model.toString(), label,
            ),
            timeoutSeconds = PROCESS_TIMEOUT_SECONDS,
        ) { "a cold checker process" }
        val output = completed.output
        check(completed.exitCode == 0) { "a cold checker process exited ${completed.exitCode}: $output" }
        val printed = ColdLane.printedFields(output)
        return ColdRun(
            checkNanos = requireNotNull(printed["checkNanos"]) { "no checkNanos in: $output" }.toLong(),
            processNanos = requireNotNull(printed["processNanos"]) { "no processNanos in: $output" }.toLong(),
            rejecting = printed["rejecting"].orEmpty().split(',').filter { it.isNotBlank() },
            status = requireNotNull(printed["status"]) { "no status in: $output" },
            complete = ColdLane.strictBoolean(requireNotNull(printed["complete"]) { "no complete in: $output" }),
        )
    }

    /**
     * The enforcement locus for "the timed process loads only the checker".
     *
     * It matches on the ARTIFACT names the kernel ships under rather than on a package, because the classpath is
     * a list of files and a jar's contents are not visible here. The list is the kernel's own coordinates plus
     * this repository's Java kits, which is what a `:adapter` runtime classpath would have dragged in had the
     * task been wired to the wrong configuration — the mistake this exists to make loud.
     */
    private fun requireCheckerOnly(classpath: String) {
        val entries = classpath.split(File.pathSeparator).filter { it.isNotBlank() }
        val foreign = entries.filter { entry ->
            val name = entry.substringAfterLast(File.separatorChar)
            name.endsWith(".jar") && FOREIGN_ARTIFACTS.any { name.startsWith(it) }
        }
        check(foreign.isEmpty()) {
            "the cold lane's classpath carries artifacts the checker does not depend on, so these numbers would " +
                "describe a JVM H5 never asked about: $foreign"
        }
        check(entries.any { it.contains("consistency") }) {
            "the cold lane's classpath carries no :consistency output at all — it would measure a JVM that " +
                "cannot check anything: $entries"
        }
    }

    private fun requiredProperty(name: String): String = ColdLane.requiredProperty(name)

    /** Recording sequence, so each diagnostic child writes its own file rather than overwriting one. */
    private var recordings = 0

    private data class ColdRun(
        val checkNanos: Long,
        val processNanos: Long,
        val rejecting: List<String>,
        val status: String,
        val complete: Boolean,
    )

    private companion object {
        /** H5-b's own floor on the sample count; a smaller run is a different measurement, not a cheaper one. */
        const val MINIMUM_PROCESSES = ColdLane.MINIMUM_OBSERVATIONS

        /** Liveness fuse for a stuck child, not a latency bar. */
        const val PROCESS_TIMEOUT_SECONDS = 120L

        val FOREIGN_ARTIFACTS = listOf(
            "a12-kernel", "kernel-md", "kernel-core", "kernel-base", "a12-base",
            "rulekit", "modelkit", "schemakit", "adapter", "interpreter", "core", "edit",
            "groovy", "jackson",
        )
    }
}
