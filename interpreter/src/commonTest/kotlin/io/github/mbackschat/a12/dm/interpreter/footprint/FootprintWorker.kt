package io.github.mbackschat.a12.dm.interpreter.footprint

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter

internal data class FootprintSampling(
    val scales: List<Int>,
    val warmup: Int,
    val repetitions: Int,
)

internal data class FootprintWorkerConfig(
    val commit: String,
    val invocationId: String,
    val sampling: FootprintSampling,
) {
    val scales: List<Int> get() = sampling.scales
    val warmup: Int get() = sampling.warmup
    val repetitions: Int get() = sampling.repetitions
}

internal data class FootprintWorkerRequest(
    val config: FootprintWorkerConfig,
    val outputPath: String,
) {
    companion object {
        fun fromEnvironment(platform: FootprintPlatform): FootprintWorkerRequest {
            fun required(name: String): String =
                platform.environment(name)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("$name is required for an enabled footprint worker")

            val scales = required("FOOTPRINT_SCALES").split(',').mapIndexed { index, raw ->
                raw.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("FOOTPRINT_SCALES[$index] is not an integer: '$raw'")
            }
            return FootprintWorkerRequest(
                config = FootprintWorkerConfig(
                    commit = required("FOOTPRINT_COMMIT"),
                    invocationId = required("FOOTPRINT_INVOCATION_ID"),
                    sampling = FootprintSampling(
                        scales = scales,
                        warmup = required("FOOTPRINT_WARMUP").toIntOrNull()
                            ?: throw IllegalArgumentException("FOOTPRINT_WARMUP must be an integer"),
                        repetitions = required("FOOTPRINT_REPETITIONS").toIntOrNull()
                            ?: throw IllegalArgumentException("FOOTPRINT_REPETITIONS must be an integer"),
                    ),
                ),
                outputPath = required("FOOTPRINT_OUTPUT"),
            )
        }

        fun enabled(platform: FootprintPlatform): Boolean =
            platform.environment("FOOTPRINT_ENABLED")?.equals("true", ignoreCase = true) == true
    }
}

internal interface FootprintOwnerReference {
    fun refersTo(owner: Any): Boolean
    fun isReleased(): Boolean
}

internal data class FootprintAllocation<T>(
    val value: T,
    val bytes: Long,
)

internal interface FootprintPlatform {
    val host: FootprintHost
    val allocationMethod: String

    fun environment(name: String): String?
    fun runtime(): FootprintRuntime
    suspend fun requireExplicitGc(): FootprintGc
    suspend fun postGcUsedBytes(): Long
    suspend fun <T> measureAllocated(block: () -> T): FootprintAllocation<T>
    fun ownerReference(owner: Any): FootprintOwnerReference
    suspend fun ownersReleased(references: List<FootprintOwnerReference>): Boolean
    fun reachabilityFence(vararg owners: Any)
    fun sha256(content: String): String
    fun write(path: String, content: String)
}

internal object FootprintWorker {

    suspend fun run(config: FootprintWorkerConfig, platform: FootprintPlatform): FootprintFork {
        val gc = platform.requireExplicitGc()
        require(gc.explicit) { "explicit GC is unavailable on ${platform.host}" }
        require(gc.viable) { "explicit GC did not produce observable collection on ${platform.host}" }

        val samples = buildList {
            FootprintScenario.entries.forEach { scenario ->
                config.scales.forEach { scale ->
                    add(measureSample(config, platform, scenario, scale))
                }
            }
        }
        val fork = FootprintFork(
            protocolVersion = 1,
            commit = config.commit,
            host = platform.host,
            invocationId = config.invocationId,
            runtime = platform.runtime(),
            gc = gc,
            allocationMethod = platform.allocationMethod,
            scales = config.scales.toList(),
            warmup = config.warmup,
            repetitions = config.repetitions,
            samples = samples,
        )
        return FootprintProtocol.validateSingleFork(fork, config.scales, FootprintScenario.entries)
    }

    private suspend fun measureSample(
        config: FootprintWorkerConfig,
        platform: FootprintPlatform,
        scenario: FootprintScenario,
        scale: Int,
    ): FootprintSample {
        val live = measureLive(config, platform, scenario, scale)
        require(platform.ownersReleased(live.references)) {
            "${scenario.id}/$scale retained a discarded interpreter/document owner"
        }
        live.observer.sweepReleasedOwners()
        val afterOwnerRelease = live.observer.logicalEntries()
        require(afterOwnerRelease == 0) {
            "${scenario.id}/$scale cache observer retained $afterOwnerRelease logical entries after owner release"
        }
        return live.sample.copy(
            cacheEntries = live.sample.cacheEntries.copy(afterOwnerRelease = afterOwnerRelease),
            ownersReleased = true,
        )
    }

    private suspend fun measureLive(
        config: FootprintWorkerConfig,
        platform: FootprintPlatform,
        scenario: FootprintScenario,
        scale: Int,
    ): LiveMeasurement {
        val retainedBefore = platform.postGcUsedBytes()
        val fixture = FootprintScenarios.fixture(scenario, scale)
        val observer = FootprintCacheObserver(platform::ownerReference)
        val interpreter = DmInterpreter(fixture.model).also { it.cacheObserver = observer }
        val references = listOf(
            platform.ownerReference(interpreter),
            platform.ownerReference(fixture.document),
        )

        val preflight = fixture.evaluate(interpreter)
        require(preflight.supported) { "${scenario.id}/$scale entered an unsupported semantic path" }
        require(preflight.outcome == scenario.expectedOutcome) {
            "${scenario.id}/$scale semantic outcome '${preflight.outcome}' != '${scenario.expectedOutcome}'"
        }
        require(preflight.cardinality == scenario.expectedCardinality(scale)) {
            "${scenario.id}/$scale result cardinality ${preflight.cardinality} != " +
                scenario.expectedCardinality(scale)
        }
        repeat(config.warmup) {
            val warmed = fixture.evaluate(interpreter)
            require(warmed == preflight) {
                "${scenario.id}/$scale changed semantic result during warmup: $preflight -> $warmed"
            }
        }

        val allocated = platform.measureAllocated {
            repeat(config.repetitions) {
                val current = fixture.evaluate(interpreter)
                require(current == preflight) {
                    "${scenario.id}/$scale changed semantic result during measurement: $preflight -> $current"
                }
            }
            preflight
        }
        val retained = platform.postGcUsedBytes() - retainedBefore
        platform.reachabilityFence(interpreter, fixture.document)
        require(allocated.bytes > 0) {
            "${scenario.id}/$scale allocation sample was ${allocated.bytes}; invalid samples are rejected, not clamped"
        }
        require(retained > 0) {
            "${scenario.id}/$scale retained sample was $retained; noisy samples are rejected, not clamped"
        }

        return LiveMeasurement(
            sample = FootprintSample(
                scenario = scenario,
                scenarioIdentity = platform.sha256(fixture.contentDefinition),
                scale = scale,
                retainedBytes = retained,
                allocatedBytes = allocated.bytes,
                cacheEntries = observer.snapshot(afterOwnerRelease = -1),
                ownersReleased = false,
                supported = allocated.value.supported,
                semanticOutcome = allocated.value.outcome,
                resultCardinality = allocated.value.cardinality,
            ),
            references = references,
            observer = observer,
        )
    }

    private data class LiveMeasurement(
        val sample: FootprintSample,
        val references: List<FootprintOwnerReference>,
        val observer: FootprintCacheObserver,
    )
}
