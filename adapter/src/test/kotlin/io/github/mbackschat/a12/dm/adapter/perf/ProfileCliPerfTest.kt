package io.github.mbackschat.a12.dm.adapter.perf

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.KernelAdapter
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

/**
 * Opt-in, real-JVM-process catastrophe canary for profile-shaped model comprehension and diff reads. It
 * catches an algorithmic blowup and **does not certify linearity**: the floor sits between a linear median
 * (~4x) and a quadratic one (~16x), so a real regression that stays under it passes here by design.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.profile.cli", matches = "true")
class ProfileCliPerfTest {

    @Test
    fun describeAndReportSurfacesStayWithinTheNormalizedProfileCatastropheFloor(@TempDir output: Path) {
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
    private val treeSummary = Regex(
        """fields (\d+) · groups (\d+) · rules (\d+) · computations (\d+) · repeatable \d+""",
    )

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
                // Re-measure before calling a breach real. This canary is the estate's MOST load-sensitive
                // observation — it spawns real JVM processes and reads wall clock, so a shared-vCPU runner
                // inflates a ratio far more easily than an in-process timing does. The happy path still costs
                // exactly one estimate; only a breach pays for the repeats that separate load from a blowup.
                PerfHarness.assertGrowthBelowCatastropheFloor(
                    "${verb.id} ${small.id}->${large.id} normalized profile",
                ) {
                    PerfHarness.pairedGrowth(
                        small = small.counts.work(verb),
                        large = large.counts.work(verb),
                        pairs = pairs,
                    ) { work ->
                        val profile = checkNotNull(byWork[work])
                        timedNormalizedMillis(verb, profile, runner)
                    }.also { growth ->
                        PerfHarness.progress(
                            "[profile-cli] ${verb.id} ${small.id}->${large.id} " +
                                "work=${small.counts.work(verb)}->${large.counts.work(verb)} " +
                                "normalized=${format(growth.smallMs, 4)}->${format(growth.largeMs, 4)} ms/unit " +
                                "paired-ratio=${format(growth.ratio, 2)}x",
                        )
                    }
                }
            }
        }
    }

    private fun synthesize(profilePath: Path, outputDirectory: Path, runner: CliRunner): ProfileArtifact {
        require(Files.isRegularFile(profilePath)) { "profile is missing: $profilePath" }
        val id = profilePath.fileName.toString().removeSuffix(".json")
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
        return ProfileArtifact(id, model, ProfileCounts.readModel(model))
    }

    private fun preflight(verb: CliVerb, profile: ProfileArtifact, runner: CliRunner) {
        val result = runner.run("${profile.id}-${verb.id}-preflight", verb.command(profile.model))
        result.requireSuccess()
        val actual = outputCardinality(verb, profile, result.output())
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

    private fun outputCardinality(verb: CliVerb, profile: ProfileArtifact, output: String): OutputCardinality {
        if (verb == CliVerb.REPORT_TREE_TEXT) {
            val summaries = output.lineSequence().mapNotNull(treeSummary::matchEntire).toList()
            check(summaries.size == 1) {
                "${verb.id} ${profile.id} preflight has ${summaries.size} exact tree summary lines"
            }
            val values = summaries.single().groupValues.drop(1).map(String::toInt)
            return OutputCardinality(values[0], values[1], values[2], values[3])
        }

        val envelope = json.readTree(output)
        check(envelope.path("ok").asBoolean(false)) {
            "${verb.id} ${profile.id} preflight returned a non-ok envelope"
        }
        return verb.cardinality(envelope.path("data"))
    }

    private fun format(value: Double, decimals: Int): String =
        "%.${decimals}f".format(Locale.ROOT, value)

    private enum class CliVerb(val id: String) {
        DESCRIBE("describe"),
        REPORT("report"),
        REPORT_TREE_TEXT("report-tree-text"),
        DIFF("diff");

        fun command(model: Path): List<String> = when (this) {
            DESCRIBE -> listOf("-m", model.toString(), "model", "describe")
            REPORT -> listOf("-m", model.toString(), "model", "report")
            REPORT_TREE_TEXT -> listOf(
                "--text", "-m", model.toString(), "model", "report", "--tree",
                "--include", "rules,computations",
            )
            DIFF -> listOf("model", "diff", model.toString(), model.toString())
        }

        fun cardinality(data: JsonNode): OutputCardinality = when (this) {
            DESCRIBE -> OutputCardinality(
                fields = data.path("fields").size(),
                groups = data.path("groups").size(),
                rules = 0,
                computations = 0,
            )
            REPORT, REPORT_TREE_TEXT -> OutputCardinality(
                fields = data.path("structure").path("fields").size(),
                groups = data.path("structure").path("groups").size(),
                rules = data.path("rules").size(),
                computations = data.path("computations").size(),
            )
            DIFF -> OutputCardinality(
                fields = data.path("changes").count { it.path("element").asText() == "FIELD" },
                groups = data.path("changes").count { it.path("element").asText() == "GROUP" },
                rules = data.path("changes").count { it.path("element").asText() == "RULE" },
                computations = data.path("changes").count { it.path("element").asText() == "COMPUTATION" },
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
            CliVerb.REPORT, CliVerb.REPORT_TREE_TEXT, CliVerb.DIFF -> fields + groups + rules + computations
        }

        fun cardinality(verb: CliVerb): OutputCardinality = when (verb) {
            CliVerb.DESCRIBE -> OutputCardinality(fields, groups, 0, 0)
            CliVerb.REPORT, CliVerb.REPORT_TREE_TEXT -> OutputCardinality(fields, groups, rules, computations)
            CliVerb.DIFF -> OutputCardinality(0, 0, 0, 0)
        }

        companion object {
            fun readModel(path: Path): ProfileCounts {
                val model = BoundModel.ofJson(Files.readString(path))
                val adapter = KernelAdapter.of(model)
                return ProfileCounts(
                    fields = model.fields().count().toInt(),
                    groups = model.groups().count().toInt(),
                    rules = adapter.ruleFullNames().size,
                    computations = adapter.computationFullNames().size,
                )
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
