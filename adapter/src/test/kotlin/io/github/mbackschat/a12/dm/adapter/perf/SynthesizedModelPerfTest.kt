package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.profile.ModelSynthesizer
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Opt-in interpreter + kernel workflow over a committed synthesis profile used as a diagnostic stress shape,
 * or an explicitly supplied synthesized model for focused JFR work. Model synthesis, document materialization,
 * Groovy codegen, and generated-Java compilation stay outside every evaluation clock. Run a committed stress
 * shape through the dedicated task, for example:
 *
 * ```
 * ./build.sh :adapter:synthesizedModelPerfTest \
 *   -Dperf.synth.profile=compact -Dperf.synth.op=validate
 * ```
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.synthesized", matches = "true")
class SynthesizedModelPerfTest {

    @Test
    fun pairedGrowthOverAnExactNestedLeafCount() {
        val seed = System.getProperty("perf.synth.seed", "42").toLong()
        val artifact = prepareModel(seed)
        val small = System.getProperty("perf.n", artifact.axis.defaultSmallScale.toString()).toInt()
        val large = Math.multiplyExact(small, 4)
        val op = PerfOp.valueOf(System.getProperty("perf.synth.op", "validate").uppercase())
        val pairs = System.getProperty("perf.synth.pairs", "3").toInt()
        val warmup = System.getProperty("perf.synth.warmup", "12").toInt()
        val iterations = System.getProperty("perf.synth.iters", "3").toInt()
        require(small > 0) { "perf.n must be positive" }
        require(pairs > 0 && pairs % 2 == 1) { "perf.synth.pairs must be a positive odd number" }
        require(warmup >= 0) { "perf.synth.warmup must not be negative" }
        require(iterations > 0) { "perf.synth.iters must be positive" }
        val modelJson = artifact.modelJson
        val loadedAt = System.nanoTime()
        val model = ModelLoader.load(modelJson)
        val modelLoadMs = elapsedMillis(loadedAt)
        val generator = SeedGenerator(model)
        val clock = EvalClock(FIXED_DAY, "${FIXED_DAY}T12:00:00")
        val bindings = PerfRuntimeBindings.builder()
            .clock(FIXED_DAY, clock.now, kernelInstant(clock, model.timeZone))
            .customCondition(
                "SynthCustomCheck",
                "constant-false-v1",
                CustomCondition { _, _, _, _ -> false },
                constantKernelCondition(false),
            )
            .build()
        val scenario = PerfScenario(
            name = "synthesized-${artifact.id}-$op-callback-false-clock-fixed",
            kernelModel = modelJson,
            interpModel = model,
            op = op,
        ) { sink, n, _ ->
            generator.populateExactLeafRows(
                sink = sink,
                leafGroup = artifact.axis.leafGroup,
                totalLeafRows = n,
                seed = seed,
                unrelatedRows = 1,
            )
        }.withRuntimeBindings(bindings).withDiagnosticMetadata(
            DiagnosticScenarioMetadata(
                resultRegime = if (op == PerfOp.VALIDATE) {
                    ResultRegime.VALIDATION_MIXED
                } else {
                    ResultRegime.COMPUTE_MIXED
                },
                complexity = artifact.axis.complexity,
                generator = GeneratorIdentity(
                    name = "SeedGenerator.populateExactLeafRows",
                    seed = seed,
                    parameters = mapOf("leaf" to artifact.axis.leafGroup, "unrelatedRows" to "1"),
                ),
                scaleAxis = "leaf rows at ${artifact.axis.leafGroup}",
                fairJavaCapable = true,
            ),
        )

        PerfHarness.progress(
            "[profile] source=${artifact.modelPath} op=$op leaf=${artifact.axis.leafGroup} seed=$seed " +
                "curve=$small->$large " +
                "pairs=$pairs warmup=$warmup iterations=$iterations",
        )

        measureInterpreterRegimes(scenario, small, large, modelLoadMs, warmup, iterations)
        val curve = PerfHarness.step("synthesized $op paired tri-engine $small->$large") {
            PerfHarness.pairedScenarioGrowth(scenario, small, large, pairs, warmup, iterations)
        }
        PerfHarness.progress(PerfHarness.format(curve.small, "synthesized-profile paired row"))
        PerfHarness.progress(PerfHarness.format(curve.large, "synthesized-profile paired row"))
        PerfHarness.progress(
            "[profile] exact-growth model=${artifact.modelPath.fileName} op=$op leaf=${artifact.axis.leafGroup} " +
                "small=${curve.small.identity.label}/${curve.small.identity.fingerprint} " +
                "large=${curve.large.identity.label}/${curve.large.identity.fingerprint} " +
                "interp=${formatRatio(curve.interpreterGrowth.ratio)}x " +
                "groovy=${formatRatio(curve.groovyGrowth.ratio)}x " +
                "java=${formatRatio(curve.javaGrowth.ratio)}x " +
                "(linear≈4x, quadratic≈16x)",
        )
    }

    private fun prepareModel(seed: Long): ModelArtifact {
        val external = System.getProperty("perf.synth.model")
        if (external != null) {
            val path = Path.of(external)
            return ModelArtifact(
                id = path.fileName.toString(),
                modelPath = path,
                modelJson = Files.readString(path),
                axis = ScaleAxis(
                    leafGroup = requiredProperty("perf.synth.leaf"),
                    complexity = Complexity.valueOf(requiredProperty("perf.synth.complexity").uppercase()),
                    defaultSmallScale = 1_000,
                ),
            )
        }

        val profile = SynthesizedProfileScenario.named(
            System.getProperty("perf.synth.profile", SynthesizedProfileScenario.COMPACT.id),
        )
        val profilePath = Path.of(requiredProperty("perf.synth.profileDir")).resolve(profile.fileName)
        require(Files.isRegularFile(profilePath)) { "committed synthesis-profile stress shape is missing: $profilePath" }
        val outputDirectory = Path.of(
            System.getProperty("perf.synth.outputDir", "build/profile-interpreter-performance"),
        )
        Files.createDirectories(outputDirectory)
        val output = outputDirectory.resolve("${profile.id}-seed-$seed.dm.json")
        val modelJson = PerfHarness.step("synthesize ${profile.fileName} outside evaluation clocks") {
            ModelSynthesizer.synthesize(Files.readString(profilePath), seed)
        }
        Files.writeString(output, modelJson)
        return ModelArtifact(
            id = profile.id,
            modelPath = output,
            modelJson = modelJson,
            axis = ScaleAxis(profile.leafGroup, profile.complexity, profile.defaultSmallScale),
        )
    }

    private fun measureInterpreterRegimes(
        scenario: PerfScenario,
        small: Int,
        large: Int,
        modelLoadMs: Double,
        warmup: Int,
        iterations: Int,
    ) {
        PerfHarness.step("exact N/4N semantic preflight across all three engines") {
            PerfHarness.assertSemanticAgreement(scenario, listOf(small, large), withJava = true)
        }
        val preparedAt = System.nanoTime()
        val engine = InterpreterPerfEngine.build(scenario)
        val preparationMs = elapsedMillis(preparedAt)
        val instance = engine.instance(small)
        val firstAt = System.nanoTime()
        val firstCardinality = instance.run().size
        val firstEvaluationMs = elapsedMillis(firstAt)
        val firstAnswerMs = preparationMs + firstEvaluationMs
        val warm = PerfHarness.measure(warmup, iterations) { instance.run() }
        PerfHarness.progress(
            "[profile] interpreter-regimes ${PerfHarness.measurementLabel(scenario, small)} " +
                "load=${formatMillis(modelLoadMs)}ms prepare=${formatMillis(preparationMs)}ms " +
                "first=${formatMillis(firstAnswerMs)}ms first-eval=${formatMillis(firstEvaluationMs)}ms " +
                "warm=${formatMillis(warm.medianMs)}ms results=$firstCardinality",
        )
    }

    private fun elapsedMillis(startedAt: Long): Double = (System.nanoTime() - startedAt) / 1_000_000.0

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-D$name=<value> is required" }

    private fun kernelInstant(clock: EvalClock, zone: ModelTimeZone): Instant {
        val zoneId = when (zone) {
            ModelTimeZone.UTC -> ZoneOffset.UTC
            ModelTimeZone.EUROPE_BERLIN -> ZoneId.of("Europe/Berlin")
        }
        return LocalDateTime.parse(checkNotNull(clock.now)).atZone(zoneId).toInstant()
    }

    private fun formatRatio(value: Double): String = "%.2f".format(Locale.ROOT, value)

    private fun formatMillis(value: Double): String = "%.2f".format(Locale.ROOT, value)

    private data class ScaleAxis(
        val leafGroup: String,
        val complexity: Complexity,
        val defaultSmallScale: Int,
    )

    private data class ModelArtifact(
        val id: String,
        val modelPath: Path,
        val modelJson: String,
        val axis: ScaleAxis,
    )

    private companion object {
        const val FIXED_DAY = "2026-07-20"
    }
}
