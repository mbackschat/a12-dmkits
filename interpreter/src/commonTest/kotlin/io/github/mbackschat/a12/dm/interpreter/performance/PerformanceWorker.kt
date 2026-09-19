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

    /**
     * CPU time consumed by the MEASURING thread, in nanoseconds — the numerator of this protocol's occupancy.
     *
     * **Why the protocol needs it (IG153, measured 2026-09-19).** A timed region's wall duration says nothing
     * about whether the region *ran*. Two bundle invocations sixteen seconds apart once disagreed by 35–60 % on
     * every JVM lane, and the report presented the difference as a 24–41 % improvement, because nothing in the
     * record distinguished "this arm was slower" from "this arm was descheduled". Occupancy does: a thread that
     * owned its core reads cpu ≈ wall, and one that waited on a busy machine or on cold I/O reads cpu < wall.
     *
     * **The JVM reports THREAD time; Node can only report process time, and the difference is measured.** On
     * the JVM a thread clock is available and is the right numerator: process CPU there includes the JIT and GC
     * threads, which legitimately push a total above wall, so a process-time ratio would read above 1.0 exactly
     * when a first-answer measurement works hardest. Node has no per-thread CPU API in stable form, so
     * `process.cpuUsage()` is all this carrier can give — and it counts the libuv pool and V8's background
     * threads, which is why Node occupancy measures **1.041–2.355** on an idle machine against the JVM's
     * **0.859–0.994** (2026-09-19). Both fall when the process is starved of cycles, which is the signal; they
     * simply have different healthy baselines, so [PerformanceProtocol.minOccupancy] is keyed by host.
     */
    fun cpuNanoTime(): Long

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
                protocolVersion = 2,
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
        val startedCpu = platform.cpuNanoTime()
        val started = platform.nanoTime()
        val result = fixture.evaluate(DmInterpreter(fixture.model))
        val elapsed = elapsedMillis(started, platform.nanoTime())
        val cpu = cpuMillis(startedCpu, platform.cpuNanoTime())
        return listOf(result.toSample(scale, listOf(elapsed), listOf(cpu)))
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
            PreparedScale(fixture.scale, fixture, interpreter, preflight, mutableListOf(), mutableListOf())
        }
        repeat(config.repetitions) {
            prepared.forEach { sample ->
                val startedCpu = platform.cpuNanoTime()
                val started = platform.nanoTime()
                val current = sample.fixture.evaluate(sample.interpreter)
                val elapsed = elapsedMillis(started, platform.nanoTime())
                val cpu = cpuMillis(startedCpu, platform.cpuNanoTime())
                require(current == sample.preflight) {
                    "${config.scenario.id}/${sample.scale}: semantic outcome changed during measurement"
                }
                sample.timings += elapsed
                sample.cpu += cpu
            }
        }
        return prepared.map { it.preflight.toSample(it.scale, it.timings, it.cpu) }
    }

    private fun elapsedMillis(started: Long, ended: Long): Double {
        require(ended > started) { "monotonic clock did not advance" }
        return (ended - started) / 1_000_000.0
    }

    /**
     * CPU milliseconds across the same region, floored at zero rather than required to advance.
     *
     * A thread CPU clock legitimately reports no progress where a wall clock must: its granularity is coarser
     * than `nanoTime` on some platforms, so a very short region can read zero. Requiring advancement here would
     * turn a resolution limit into a measurement failure — the occupancy that results is simply 0, which the
     * bar below judges rather than crashes on.
     */
    private fun cpuMillis(started: Long, ended: Long): Double =
        if (ended <= started) 0.0 else (ended - started) / 1_000_000.0

    private fun PerformanceSemanticResult.toSample(
        scale: Int,
        timings: List<Double>,
        cpu: List<Double>,
    ): PerformanceScaleSample =
        PerformanceScaleSample(scale, timings.toList(), cpu.toList(), cardinality, supported, outcome)

    private data class PreparedScale(
        val scale: Int,
        val fixture: PerformanceFixture,
        val interpreter: DmInterpreter,
        val preflight: PerformanceSemanticResult,
        val timings: MutableList<Double>,
        val cpu: MutableList<Double>,
    )
}
