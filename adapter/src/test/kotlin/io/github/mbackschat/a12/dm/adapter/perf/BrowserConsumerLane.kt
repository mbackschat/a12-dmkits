package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * What every `:adapter` lane that measures the checker in a real browser has to do before it can measure.
 *
 * Both browser arms — [CheckerColdBrowserPerfTest] and [CheckerPreparedBrowserPerfTest] — serve the same
 * synthesized workloads out of the consumer project's `public/`, build the same bundle and sampler through that
 * project's own scripts, and run their sampler as a tool child with the resolved tool's directory prepended to
 * `PATH`. Only the page and the sampling shape differ.
 *
 * **Extracted when the second arm arrived rather than after a third**, which is the lesson the cold arms taught
 * an hour earlier: three copies of a small helper is where a set of them stops being reviewable, and the
 * machine-specific half — a resolved tool's `PATH` — is the half nobody notices is wrong until a different
 * machine runs it.
 *
 * The bundle build deliberately sits OUTSIDE any estimate. It is part of no observation, and rebuilding it per
 * re-measurement attempt would add a minute to each for nothing.
 */
internal object BrowserConsumerLane {

    /**
     * Write the synthesized models where the page can fetch them, returning the URL path per scale.
     *
     * Vite serves `public/` at the site root, so the returned `perf/synth-<n>.dm.json` is what a page asks for.
     */
    fun stageWorkloads(consumer: Path, scales: List<Int>): Map<Int, String> {
        val servedDir = consumer.resolve("public").resolve("perf")
        Files.createDirectories(servedDir)
        return scales.associateWith { scale ->
            val name = "synth-$scale.dm.json"
            Files.writeString(servedDir.resolve(name), ProfileSynthesisTargetProfiles.modelJson(scale))
            "perf/$name"
        }
    }

    /** Build the bundle and the samplers, through the consumer project's own scripts. */
    fun buildBundle(consumer: Path, pnpm: Path, timeoutSeconds: Long) {
        runToCompletion(consumer, listOf(pnpm.toString(), "run", "build"), "the browser bundle build", pnpm, timeoutSeconds)
        runToCompletion(consumer, listOf(pnpm.toString(), "run", "build:scripts"), "the sampler build", pnpm, timeoutSeconds)
    }

    /**
     * Run one tool child to completion, with its own directory on `PATH`.
     *
     * The prepended directory is what lets `pnpm` find the `node` this build resolved rather than whatever the
     * ambient `PATH` offers — the lane pins a runtime, and a child that resolved a different one would measure
     * something the record does not name.
     */
    fun runToCompletion(
        workingDir: Path,
        command: List<String>,
        what: String,
        tool: Path,
        timeoutSeconds: Long,
    ): String {
        val toolDir = requireNotNull(tool.parent) { "$tool has no parent directory" }
        val completed = ChildProcess.runWithFuse(
            command = command,
            directory = workingDir,
            environment = mapOf("PATH" to "$toolDir${File.pathSeparator}${System.getenv("PATH")}"),
            timeoutSeconds = timeoutSeconds,
        ) { what }
        check(completed.exitCode == 0) { "$what exited ${completed.exitCode}: ${completed.output}" }
        return completed.output
    }
}
