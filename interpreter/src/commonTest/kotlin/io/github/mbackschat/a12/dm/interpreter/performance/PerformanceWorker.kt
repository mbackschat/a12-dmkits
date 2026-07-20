package io.github.mbackschat.a12.dm.interpreter.performance

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter

internal data class PerformanceWorkerConfig(
    val commit: String,
    val runner: String,
    val invocationId: String,
    val scenario: PerformanceScenario,
    val warmup: Int,
    val repetitions: Int,
)

internal data class PerformanceWorkerRequest(
    val config: PerformanceWorkerConfig,
    val outputPath: String,
) {
    companion object {
        fun enabled(platform: PerformancePlatform): Boolean =
            platform.environment("PERFORMANCE_ENABLED")?.equals("true", ignoreCase = true) == true

        fun fromEnvironment(platform: PerformancePlatform): PerformanceWorkerRequest {
            fun required(name: String): String =
                platform.environment(name)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("$name is required for an enabled performance worker")
            return PerformanceWorkerRequest(
                PerformanceWorkerConfig(
                    commit = required("PERFORMANCE_COMMIT"),
                    runner = required("PERFORMANCE_RUNNER"),
                    invocationId = required("PERFORMANCE_INVOCATION_ID"),
                    scenario = PerformanceScenario.fromId(required("PERFORMANCE_SCENARIO")),
                    warmup = required("PERFORMANCE_WARMUP").toIntOrNull()
                        ?: throw IllegalArgumentException("PERFORMANCE_WARMUP must be an integer"),
                    repetitions = required("PERFORMANCE_REPETITIONS").toIntOrNull()
                        ?: throw IllegalArgumentException("PERFORMANCE_REPETITIONS must be an integer"),
                ),
                required("PERFORMANCE_OUTPUT"),
            )
        }
    }
}

internal interface PerformancePlatform {
    val host: PerformanceHost
    fun environment(name: String): String?
    fun runtime(): PerformanceRuntime
    fun nanoTime(): Long
    fun sha256(content: String): String
    fun write(path: String, content: String)
}

internal object PerformanceWorker {

    fun run(config: PerformanceWorkerConfig, platform: PerformancePlatform): PerformanceFork {
        require(config.scenario.host == platform.host) {
            "${config.scenario.id} targets ${config.scenario.host}, worker is ${platform.host}"
        }
        val fixtures = config.scenario.scales.map { scale ->
            PerformanceScenarios.fixture(config.scenario, scale)
        }
        val samples = when (config.scenario.mode) {
            PerformanceMode.FIRST_ANSWER -> firstAnswer(config, platform, fixtures.single())
            PerformanceMode.WARM_THROUGHPUT -> throughput(config, platform, fixtures)
        }
        val identity = platform.sha256(PerformanceScenarios.identityContent(fixtures))
        return PerformanceProtocol.validateFork(
            PerformanceFork(
                protocolVersion = 1,
                commit = config.commit,
                runner = config.runner,
                invocationId = config.invocationId,
                host = platform.host,
                runtime = platform.runtime(),
                scenario = config.scenario,
                scenarioIdentity = identity,
                warmup = config.warmup,
                samples = samples,
            ),
        )
    }

    private fun firstAnswer(
        config: PerformanceWorkerConfig,
        platform: PerformancePlatform,
        fixture: PerformanceFixture,
    ): List<PerformanceScaleSample> {
        require(config.warmup == 0) { "first-answer workers cannot warm up" }
        require(config.repetitions == 1) { "first-answer workers emit exactly one sample" }
        val scale = config.scenario.scales.single()
        val started = platform.nanoTime()
        val result = fixture.evaluate(DmInterpreter(fixture.model))
        val elapsed = elapsedMillis(started, platform.nanoTime())
        return listOf(result.toSample(scale, listOf(elapsed)))
    }

    private fun throughput(
        config: PerformanceWorkerConfig,
        platform: PerformancePlatform,
        fixtures: List<PerformanceFixture>,
    ): List<PerformanceScaleSample> {
        require(config.warmup > 0) { "throughput workers require warmup" }
        require(config.repetitions > 0 && config.repetitions % 2 == 1) {
            "throughput repetitions must be positive and odd"
        }
        val prepared = fixtures.map { fixture ->
            val interpreter = DmInterpreter(fixture.model)
            val preflight = fixture.evaluate(interpreter)
            repeat(config.warmup) { fixture.evaluate(interpreter) }
            PreparedScale(fixture.scale, fixture, interpreter, preflight, mutableListOf())
        }
        repeat(config.repetitions) {
            prepared.forEach { sample ->
                val started = platform.nanoTime()
                val current = sample.fixture.evaluate(sample.interpreter)
                val elapsed = elapsedMillis(started, platform.nanoTime())
                require(current == sample.preflight) {
                    "${config.scenario.id}/${sample.scale}: semantic outcome changed during measurement"
                }
                sample.timings += elapsed
            }
        }
        return prepared.map { it.preflight.toSample(it.scale, it.timings) }
    }

    private fun elapsedMillis(started: Long, ended: Long): Double {
        require(ended > started) { "monotonic clock did not advance" }
        return (ended - started) / 1_000_000.0
    }

    private fun PerformanceSemanticResult.toSample(
        scale: Int,
        timings: List<Double>,
    ): PerformanceScaleSample =
        PerformanceScaleSample(scale, timings.toList(), cardinality, supported, outcome)

    private data class PreparedScale(
        val scale: Int,
        val fixture: PerformanceFixture,
        val interpreter: DmInterpreter,
        val preflight: PerformanceSemanticResult,
        val timings: MutableList<Double>,
    )
}
