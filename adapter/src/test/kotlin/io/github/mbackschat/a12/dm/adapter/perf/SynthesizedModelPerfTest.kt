package io.github.mbackschat.a12.dm.adapter.perf

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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Opt-in interpreter + kernel curve over a model produced by `dmtool profile synthesize`. Model synthesis,
 * document materialization, Groovy codegen, and generated-Java compilation stay outside every warm clock.
 */
@EnabledIfSystemProperty(named = "perf.synthesized", matches = "true")
class SynthesizedModelPerfTest {

    @Test
    fun pairedGrowthOverAnExactNestedLeafCount() {
        val modelPath = requiredProperty("perf.synth.model")
        val leaf = requiredProperty("perf.synth.leaf")
        val small = System.getProperty("perf.n", "1000").toInt()
        val large = Math.multiplyExact(small, 4)
        val seed = System.getProperty("perf.synth.seed", "42").toLong()
        val op = PerfOp.valueOf(System.getProperty("perf.synth.op", "validate").uppercase())
        val complexity = Complexity.valueOf(requiredProperty("perf.synth.complexity").uppercase())
        val pairs = System.getProperty("perf.synth.pairs", "3").toInt()
        val warmup = System.getProperty("perf.synth.warmup", "12").toInt()
        val iterations = System.getProperty("perf.synth.iters", "3").toInt()
        require(small > 0) { "perf.n must be positive" }
        require(pairs > 0 && pairs % 2 == 1) { "perf.synth.pairs must be a positive odd number" }
        require(warmup >= 0) { "perf.synth.warmup must not be negative" }
        require(iterations > 0) { "perf.synth.iters must be positive" }
        val modelJson = Files.readString(Path.of(modelPath))
        val model = ModelLoader.load(modelJson)
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
            name = "synthesized-${Path.of(modelPath).fileName}-$op-callback-false-clock-fixed",
            kernelModel = modelJson,
            interpModel = model,
            op = op,
        ) { sink, n, _ ->
            generator.populateExactLeafRows(
                sink = sink,
                leafGroup = leaf,
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
                complexity = complexity,
                generator = GeneratorIdentity(
                    name = "SeedGenerator.populateExactLeafRows",
                    seed = seed,
                    parameters = mapOf("leaf" to leaf, "unrelatedRows" to "1"),
                ),
                scaleAxis = "leaf rows at $leaf",
                fairJavaCapable = true,
            ),
        )

        PerfHarness.progress(
            "[profile] model=$modelPath op=$op leaf=$leaf seed=$seed curve=$small->$large " +
                "pairs=$pairs warmup=$warmup iterations=$iterations",
        )

        val curve = PerfHarness.step("synthesized $op paired tri-engine $small->$large") {
            PerfHarness.pairedScenarioGrowth(scenario, small, large, pairs, warmup, iterations)
        }
        PerfHarness.progress(PerfHarness.format(curve.small, "synthesized-profile paired row"))
        PerfHarness.progress(PerfHarness.format(curve.large, "synthesized-profile paired row"))
        PerfHarness.progress(
            "[profile] exact-growth model=${Path.of(modelPath).fileName} op=$op leaf=$leaf " +
                "small=${curve.small.identity.label}/${curve.small.identity.fingerprint} " +
                "large=${curve.large.identity.label}/${curve.large.identity.fingerprint} " +
                "interp=${formatRatio(curve.interpreterGrowth.ratio)}x " +
                "groovy=${formatRatio(curve.groovyGrowth.ratio)}x " +
                "java=${formatRatio(curve.javaGrowth.ratio)}x " +
                "(linear≈4x, quadratic≈16x)",
        )
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-D$name=<value> is required" }

    private fun kernelInstant(clock: EvalClock, zone: ModelTimeZone): Instant {
        val zoneId = when (zone) {
            ModelTimeZone.UTC -> ZoneOffset.UTC
            ModelTimeZone.EUROPE_BERLIN -> ZoneId.of("Europe/Berlin")
        }
        return LocalDateTime.parse(checkNotNull(clock.now)).atZone(zoneId).toInstant()
    }

    private fun formatRatio(value: Double): String = "%.2f".format(value)

    private companion object {
        const val FIXED_DAY = "2026-07-20"
    }
}
