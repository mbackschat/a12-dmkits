package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

/**
 * H5-b: what the Kernel-free checker costs to reach its FIRST result, measured on processes that have never
 * checked anything.
 *
 * **It asserts no budget and is not a gate.** H5 requires a budget to be ratified before the implementation it
 * gates is measured, and none is ([the plan's H5 record](../../../../../../../../../../../docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ordering)).
 * So this lane produces an informative, uncertified number for the owner to rule on, exactly the status the
 * interpreter's own NFR figures carry. It deliberately writes no anchor: an anchor IS a bar, and a bar written
 * before ratification would gate on a number nobody has approved.
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
    fun theColdFirstResultOverIndependentProcessesAtBothScales(@TempDir work: Path) {
        JdkIdentity.requireSupported()
        val classpath = requiredProperty("perf.checker.cold.classpath")
        val processes = requiredProperty("perf.checker.cold.processes").toInt()
        val programs = requiredProperty("perf.checker.cold.n").toInt()
        require(processes >= MINIMUM_PROCESSES) {
            "H5-b asks for at least $MINIMUM_PROCESSES independent processes per scale, not $processes"
        }
        require(programs > 0) { "perf.checker.cold.n must be positive" }
        requireCheckerOnly(classpath)

        listOf(programs, programs * 4).forEach { scale ->
            val model = work.resolve("synth-$scale.dm.json")
            Files.writeString(model, ProfileSynthesisTargetProfiles.modelJson(scale))
            val runs = (1..processes).map { run(classpath, model, "synth-$scale") }
            val refused = runs.map { it.rejecting }.distinct().filter { it.isNotEmpty() }
            check(refused.isEmpty()) {
                "the checker REFUSED the synthesized model at $scale programs, so these processes timed the " +
                    "rejection path rather than a complete report: $refused"
            }
            report("check", scale, runs.map { it.checkNanos })
            report("process", scale, runs.map { it.processNanos })
        }
    }

    /**
     * Nearest-rank p95 with the median beside it, which is the pair the plan's H5-b row asks for.
     *
     * The median says what the boundary usually costs and the p95 says what a user occasionally waits for; a
     * cold lane spread over independent processes has a long right tail by construction, so either number alone
     * describes a different machine. Nearest rank is the definition that needs no interpolation and therefore
     * always names an observation that actually happened.
     */
    private fun report(quantity: String, programs: Int, nanos: List<Long>) {
        val sorted = nanos.sorted()
        val p95 = sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
        PerfHarness.progress(
            "[checker-cold] $quantity programs=$programs n=${sorted.size} " +
                "median=${millis(sorted[sorted.size / 2])} p95=${millis(p95)} " +
                "min=${millis(sorted.first())} max=${millis(sorted.last())} ms",
        )
    }

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
        val process = ProcessBuilder(
            listOfNotNull(
                java.toString(),
                heap.takeIf { it.isNotBlank() }?.let { "-Xmx$it" },
                "-cp", classpath,
                "io.github.mbackschat.a12.dm.adapter.perf.CheckerColdStartMain",
                model.toString(), label,
            ),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("a cold checker process exceeded the $PROCESS_TIMEOUT_SECONDS-second harness fuse: $output")
        }
        check(process.exitValue() == 0) { "a cold checker process exited ${process.exitValue()}: $output" }
        val printed = output.lineSequence()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { (key, value) -> key to value }
        return ColdRun(
            checkNanos = requireNotNull(printed["checkNanos"]) { "no checkNanos in: $output" }.toLong(),
            processNanos = requireNotNull(printed["processNanos"]) { "no processNanos in: $output" }.toLong(),
            rejecting = printed["rejecting"].orEmpty().split(',').filter { it.isNotBlank() },
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

    private fun millis(nanos: Long): String = "%.2f".format(Locale.ROOT, nanos / 1_000_000.0)

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-D$name=<value> is required" }

    private data class ColdRun(val checkNanos: Long, val processNanos: Long, val rejecting: List<String>)

    private companion object {
        /** H5-b's own floor on the sample count; a smaller run is a different measurement, not a cheaper one. */
        const val MINIMUM_PROCESSES = 50

        /** Liveness fuse for a stuck child, not a latency bar. */
        const val PROCESS_TIMEOUT_SECONDS = 120L

        val FOREIGN_ARTIFACTS = listOf(
            "a12-kernel", "kernel-md", "kernel-core", "kernel-base", "a12-base",
            "rulekit", "modelkit", "schemakit", "adapter", "interpreter", "core", "edit",
            "groovy", "jackson",
        )
    }
}
