package io.github.mbackschat.a12.dm.adapter.perf

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

/** Opt-in, real-JVM-process catastrophe canary for profile-shaped model comprehension reads. */
@EnabledIfSystemProperty(named = "perf.profile.cli", matches = "true")
class ProfileCliPerfTest {

    @Test
    fun describeAndDefaultReportStayWithinTheNormalizedProfileCatastropheFloor(@TempDir output: Path) {
        ProfileCliCanary.run(
            launcher = Path.of(requiredProperty("perf.profile.cli.launcher")),
            profileDirectory = Path.of(requiredProperty("perf.profile.cli.profileDir")),
            outputDirectory = output,
            pairs = requiredProperty("perf.profile.cli.pairs").toInt(),
        )
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-$name=<value> is required" }
}

private object ProfileCliCanary {
    private val json = ObjectMapper()
    private val profileNames = listOf("compact.json", "depth-shaped.json", "breadth-shaped.json")

    fun run(launcher: Path, profileDirectory: Path, outputDirectory: Path, pairs: Int) {
        require(Files.isRegularFile(launcher) && Files.isExecutable(launcher)) {
            "installed dmtool launcher is missing or not executable: $launcher"
        }
        require(pairs > 0 && pairs % 2 == 1) { "pairs must be a positive odd number" }
        val runner = CliRunner(launcher, outputDirectory)
        val profiles = profileNames.map { name ->
            synthesize(profileDirectory.resolve(name), outputDirectory, runner)
        }

        CliVerb.entries.forEach { verb ->
            profiles.forEach { profile -> preflight(verb, profile, runner) }
            profiles.sortedBy { it.counts.work(verb) }.zipWithNext().forEach { (small, large) ->
                val byWork = mapOf(small.counts.work(verb) to small, large.counts.work(verb) to large)
                val growth = PerfHarness.pairedGrowth(
                    small = small.counts.work(verb),
                    large = large.counts.work(verb),
                    pairs = pairs,
                ) { work ->
                    val profile = checkNotNull(byWork[work])
                    timedNormalizedMillis(verb, profile, runner)
                }
                PerfHarness.progress(
                    "[profile-cli] ${verb.id} ${small.id}->${large.id} " +
                        "work=${small.counts.work(verb)}->${large.counts.work(verb)} " +
                        "normalized=${format(growth.smallMs, 4)}->${format(growth.largeMs, 4)} ms/unit " +
                        "paired-ratio=${format(growth.ratio, 2)}x",
                )
                check(growth.ratio < PerfBounds.SUB_QUADRATIC_MAX) {
                    "${verb.id} ${small.id}->${large.id} normalized profile growth ${growth.ratio}x reached " +
                        "the ${PerfBounds.SUB_QUADRATIC_MAX}x catastrophe floor; this canary does not certify linearity"
                }
            }
        }
    }

    private fun synthesize(profilePath: Path, outputDirectory: Path, runner: CliRunner): ProfileArtifact {
        require(Files.isRegularFile(profilePath)) { "profile is missing: $profilePath" }
        val id = profilePath.fileName.toString().removeSuffix(".json")
        val counts = ProfileCounts.read(profilePath)
        val model = outputDirectory.resolve("$id.dm.json")
        runner.run(
            "$id-synthesize",
            listOf(
                "profile", "synthesize",
                "--input", profilePath.toString(),
                "--seed", "42",
                "-o", model.toString(),
            ),
        ).requireSuccess()
        check(Files.isRegularFile(model)) { "profile synthesis did not create $model" }
        return ProfileArtifact(id, model, counts)
    }

    private fun preflight(verb: CliVerb, profile: ProfileArtifact, runner: CliRunner) {
        val result = runner.run("${profile.id}-${verb.id}-preflight", verb.command(profile.model))
        result.requireSuccess()
        val envelope = json.readTree(result.output())
        check(envelope.path("ok").asBoolean(false)) {
            "${verb.id} ${profile.id} preflight returned a non-ok envelope"
        }
        val actual = verb.cardinality(envelope.path("data"))
        val expected = profile.counts.cardinality(verb)
        check(actual == expected) {
            "${verb.id} ${profile.id} output cardinality $actual != profile cardinality $expected"
        }
    }

    private fun timedNormalizedMillis(verb: CliVerb, profile: ProfileArtifact, runner: CliRunner): Double {
        val result = runner.run("${profile.id}-${verb.id}-timed", verb.command(profile.model))
        result.requireSuccess()
        return result.elapsedMillis / profile.counts.work(verb)
    }

    private fun format(value: Double, decimals: Int): String =
        "%.${decimals}f".format(Locale.ROOT, value)

    private enum class CliVerb(val id: String) {
        DESCRIBE("describe"),
        REPORT("report");

        fun command(model: Path): List<String> = listOf("-m", model.toString(), "model", id)

        fun cardinality(data: JsonNode): OutputCardinality = when (this) {
            DESCRIBE -> OutputCardinality(
                fields = data.path("fields").size(),
                groups = data.path("groups").size(),
                rules = 0,
                computations = 0,
            )
            REPORT -> OutputCardinality(
                fields = data.path("structure").path("fields").size(),
                groups = data.path("structure").path("groups").size(),
                rules = data.path("rules").size(),
                computations = data.path("computations").size(),
            )
        }
    }

    private data class ProfileArtifact(val id: String, val model: Path, val counts: ProfileCounts)

    private data class OutputCardinality(
        val fields: Int,
        val groups: Int,
        val rules: Int,
        val computations: Int,
    )

    private data class ProfileCounts(
        val fields: Int,
        val groups: Int,
        val rules: Int,
        val computations: Int,
    ) {
        fun work(verb: CliVerb): Int = when (verb) {
            CliVerb.DESCRIBE -> fields + groups
            CliVerb.REPORT -> fields + groups + rules + computations
        }

        fun cardinality(verb: CliVerb): OutputCardinality = when (verb) {
            CliVerb.DESCRIBE -> OutputCardinality(fields, groups, 0, 0)
            CliVerb.REPORT -> OutputCardinality(fields, groups, rules, computations)
        }

        companion object {
            fun read(path: Path): ProfileCounts {
                val elements = json.readTree(path.toFile()).path("structure").path("elements")
                return ProfileCounts(
                    fields = elements.requiredCount("FIELD", path),
                    groups = elements.requiredCount("GROUP", path),
                    rules = elements.requiredCount("RULE", path),
                    computations = elements.requiredCount("COMPUTATION", path),
                )
            }

            private fun JsonNode.requiredCount(name: String, path: Path): Int {
                val value = path(name)
                require(value.canConvertToInt() && value.asInt() >= 0) {
                    "$path has no non-negative structure.elements.$name count"
                }
                return value.asInt()
            }
        }
    }

    private class CliRunner(private val launcher: Path, private val outputDirectory: Path) {
        private var sequence = 0

        init {
            Files.createDirectories(outputDirectory)
        }

        fun run(label: String, arguments: List<String>): ProcessResult {
            val stem = "%03d-%s".format(++sequence, label)
            val stdout = outputDirectory.resolve("$stem.stdout")
            val stderr = outputDirectory.resolve("$stem.stderr")
            val started = System.nanoTime()
            val process = ProcessBuilder(listOf(launcher.toString()) + arguments)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
                error("dmtool process exceeded the $PROCESS_TIMEOUT_SECONDS-second harness safety timeout: $arguments")
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000.0
            return ProcessResult(process.exitValue(), elapsedMillis, stdout, stderr)
        }
    }

    private data class ProcessResult(
        val exitCode: Int,
        val elapsedMillis: Double,
        val stdout: Path,
        val stderr: Path,
    ) {
        fun requireSuccess() {
            check(exitCode == 0) {
                "dmtool exited $exitCode; stdout=${boundedRead(stdout)} stderr=${boundedRead(stderr)}"
            }
        }

        fun output(): String = Files.readString(stdout, StandardCharsets.UTF_8)

        private fun boundedRead(path: Path): String =
            Files.readString(path, StandardCharsets.UTF_8).take(2_000)
    }

    /** Liveness fuse for a stuck child process, not an absolute latency acceptance bar. */
    private const val PROCESS_TIMEOUT_SECONDS = 180L
}
