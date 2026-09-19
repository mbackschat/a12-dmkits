package io.github.mbackschat.a12.dm.interpreter.performance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.ceil

internal data class PerformanceRuntime(
    val name: String,
    val version: String,
    val os: String,
    val arch: String,
    val pid: Long,
    val maxHeapBytes: Long,
)

internal data class PerformanceScaleSample(
    val scale: Int,
    val elapsedMillis: List<Double>,
    val cardinality: Int,
    val supported: Boolean,
    val outcome: String,
)

internal data class PerformanceFork(
    val protocolVersion: Int,
    val commit: String,
    val runner: String,
    val invocationId: String,
    val host: PerformanceHost,
    val runtime: PerformanceRuntime,
    val scenario: PerformanceScenario,
    val scenarioIdentity: String,
    val warmup: Int,
    val samples: List<PerformanceScaleSample>,
)

internal data class PerformanceSummary(
    val scenario: PerformanceScenario,
    val scenarioIdentity: String,
    val sampleCount: Int,
    val medianMillis: Double,
    val p95Millis: Double?,
    val growthRatio: Double?,
)

internal data class PerformanceRun(
    val commit: String,
    val runner: String,
    val forks: List<PerformanceFork>,
    val summaries: List<PerformanceSummary>,
)

internal object PerformanceProtocol {

    private const val VERSION = 1
    private val COMMIT = Regex("[0-9a-f]{8,64}")
    private val SHA_256 = Regex("[0-9a-f]{64}")

    fun validateFork(fork: PerformanceFork): PerformanceFork {
        require(fork.protocolVersion == VERSION) { "unsupported protocolVersion ${fork.protocolVersion}" }
        require(fork.commit.matches(COMMIT)) { "commit must be an 8–64 character lowercase hexadecimal identity" }
        require(fork.runner.isNotBlank()) { "runner must not be blank" }
        require(fork.invocationId.isNotBlank()) { "invocationId must not be blank" }
        require(fork.host == fork.scenario.host) { "${fork.scenario.id} cannot run on ${fork.host}" }
        require(fork.runtime.name.isNotBlank() && fork.runtime.version.isNotBlank()) { "runtime identity is incomplete" }
        require(fork.runtime.os.isNotBlank() && fork.runtime.arch.isNotBlank()) { "host identity is incomplete" }
        require(fork.runtime.pid > 0 && fork.runtime.maxHeapBytes > 0) { "runtime pid/heap must be positive" }
        require(fork.scenarioIdentity.matches(SHA_256)) { "scenarioIdentity must be a SHA-256 fingerprint" }
        require(fork.warmup >= 0) { "warmup must not be negative" }
        require(fork.samples.map { it.scale } == fork.scenario.scales) {
            "${fork.scenario.id}: scales ${fork.samples.map { it.scale }} != ${fork.scenario.scales}"
        }
        fork.samples.forEach { sample ->
            require(sample.elapsedMillis.isNotEmpty()) { "${fork.scenario.id}/${sample.scale}: no timings" }
            require(sample.elapsedMillis.all { it.isFinite() && it > 0.0 }) {
                "${fork.scenario.id}/${sample.scale}: timings must be finite and positive"
            }
            require(sample.supported) { "${fork.scenario.id}/${sample.scale}: unsupported semantics" }
            require(sample.cardinality >= 0) { "${fork.scenario.id}/${sample.scale}: negative cardinality" }
            require(sample.outcome.isNotBlank()) { "${fork.scenario.id}/${sample.scale}: blank outcome" }
        }
        when (fork.scenario.mode) {
            PerformanceMode.FIRST_ANSWER -> {
                require(fork.warmup == 0) { "first-answer samples cannot warm up" }
                require(fork.samples.single().elapsedMillis.size == 1) {
                    "each first-answer process must emit exactly one timing"
                }
            }
            PerformanceMode.WARM_THROUGHPUT -> {
                require(fork.warmup > 0) { "warm-throughput samples require warmup" }
                val counts = fork.samples.map { it.elapsedMillis.size }
                val count = counts.distinct().singleOrNull()
                require(count != null && count > 0 && count % 2 == 1) {
                    "warm-throughput scales need the same positive odd sample count"
                }
            }
        }
        return fork
    }

    fun aggregate(
        forks: List<PerformanceFork>,
        requiredForks: Int,
        expectedScenarios: List<PerformanceScenario> = PerformanceScenario.entries,
    ): PerformanceRun {
        require(forks.isNotEmpty()) { "at least one performance fork is required" }
        require(requiredForks > 0) { "requiredForks must be positive" }
        forks.forEach(::validateFork)
        require(forks.map { it.commit }.distinct().size == 1) { "all forks must measure one commit" }
        require(forks.map { it.runner }.distinct().size == 1) { "all forks must name one pinned runner" }
        require(forks.map { it.host to it.runtime.pid }.distinct().size == forks.size) {
            "every performance fork must have a distinct host/PID"
        }
        require(expectedScenarios.isNotEmpty() && expectedScenarios.distinct() == expectedScenarios) {
            "expectedScenarios must be non-empty and unique"
        }
        val summaries = expectedScenarios.map { scenario ->
            val matching = forks.filter { it.scenario == scenario }
            require(matching.isNotEmpty()) { "${scenario.id}: no process result" }
            require(matching.map { it.scenarioIdentity }.distinct().size == 1) {
                "${scenario.id}: content fingerprint differs across forks"
            }
            when (scenario.mode) {
                PerformanceMode.FIRST_ANSWER -> {
                    require(matching.size >= requiredForks) {
                        "${scenario.id}: ${matching.size} forks < required $requiredForks"
                    }
                    val values = matching.map { it.samples.single().elapsedMillis.single() }
                    PerformanceSummary(
                        scenario, matching.first().scenarioIdentity, values.size,
                        median(values), nearestRank(values, 0.95), null,
                    )
                }
                PerformanceMode.WARM_THROUGHPUT -> {
                    require(matching.size >= requiredForks) {
                        "${scenario.id}: ${matching.size} forks < required $requiredForks"
                    }
                    // Two levels, deliberately: each process reports the median of its own odd repetition
                    // set, and the row is the median of those per-process medians. That inner median is
                    // what makes this the one timed quantity in the estate whose run-to-run spread fits
                    // under NFR #1's 5% bar — 1.465x within a run collapses to 1.059x across ten of them
                    // (IG152, measured 2026-09-19). Until that date the protocol admitted exactly one
                    // throughput process, so the outer median did not exist and no bar could be built.
                    val perForkMedian = matching.map { median(it.samples.last().elapsedMillis) }
                    val perForkGrowth = matching.mapNotNull { fork ->
                        fork.samples.takeIf { it.size == 2 }?.let { samples ->
                            median(
                                samples[0].elapsedMillis.indices.map { index ->
                                    samples[1].elapsedMillis[index] / samples[0].elapsedMillis[index]
                                },
                            )
                        }
                    }
                    PerformanceSummary(
                        scenario, matching.first().scenarioIdentity, perForkMedian.size,
                        median(perForkMedian), null, perForkGrowth.takeIf { it.isNotEmpty() }?.let(::median),
                    )
                }
            }
        }
        return PerformanceRun(forks.first().commit, forks.first().runner, forks, summaries)
    }

    fun render(fork: PerformanceFork): String = JsonObject(
        mapOf(
            "protocolVersion" to JsonPrimitive(fork.protocolVersion),
            "commit" to JsonPrimitive(fork.commit),
            "runner" to JsonPrimitive(fork.runner),
            "invocationId" to JsonPrimitive(fork.invocationId),
            "host" to JsonPrimitive(fork.host.name),
            "runtime" to runtimeJson(fork.runtime),
            "scenario" to JsonPrimitive(fork.scenario.id),
            "scenarioVersion" to JsonPrimitive(fork.scenario.version),
            "identityLabel" to JsonPrimitive("${fork.scenario.id}@${fork.scenario.version}"),
            "scenarioIdentity" to JsonPrimitive(fork.scenarioIdentity),
            "warmup" to JsonPrimitive(fork.warmup),
            "samples" to JsonArray(fork.samples.map(::sampleJson)),
        ),
    ).toString()

    fun parse(raw: String): PerformanceFork {
        val root = Json.parseToJsonElement(raw).jsonObject
        val runtime = root.requiredObject("runtime")
        return PerformanceFork(
            protocolVersion = root.requiredInt("protocolVersion"),
            commit = root.requiredString("commit"),
            runner = root.requiredString("runner"),
            invocationId = root.requiredString("invocationId"),
            host = PerformanceHost.valueOf(root.requiredString("host")),
            runtime = PerformanceRuntime(
                runtime.requiredString("name"),
                runtime.requiredString("version"),
                runtime.requiredString("os"),
                runtime.requiredString("arch"),
                runtime.requiredLong("pid"),
                runtime.requiredLong("maxHeapBytes"),
            ),
            scenario = PerformanceScenario.fromId(root.requiredString("scenario")),
            scenarioIdentity = root.requiredString("scenarioIdentity"),
            warmup = root.requiredInt("warmup"),
            samples = root.requiredArray("samples").map { element ->
                val sample = element.jsonObject
                PerformanceScaleSample(
                    scale = sample.requiredInt("scale"),
                    elapsedMillis = sample.requiredArray("elapsedMillis").mapIndexed { index, timing ->
                        timing.jsonPrimitive.doubleOrNull
                            ?: throw IllegalArgumentException("elapsedMillis[$index] must be a number")
                    },
                    cardinality = sample.requiredInt("cardinality"),
                    supported = sample.requiredBoolean("supported"),
                    outcome = sample.requiredString("outcome"),
                )
            },
        )
    }

    fun render(run: PerformanceRun): String = JsonObject(
        mapOf(
            "protocolVersion" to JsonPrimitive(VERSION),
            "informativeOnly" to JsonPrimitive(true),
            "commit" to JsonPrimitive(run.commit),
            "runner" to JsonPrimitive(run.runner),
            "summaries" to JsonArray(run.summaries.map { summary ->
                JsonObject(
                    buildMap {
                        put("scenario", JsonPrimitive(summary.scenario.id))
                        put("scenarioVersion", JsonPrimitive(summary.scenario.version))
                        put("identityLabel", JsonPrimitive("${summary.scenario.id}@${summary.scenario.version}"))
                        put("scenarioIdentity", JsonPrimitive(summary.scenarioIdentity))
                        put("sampleCount", JsonPrimitive(summary.sampleCount))
                        put("medianMillis", JsonPrimitive(summary.medianMillis))
                        summary.p95Millis?.let { put("nearestRankP95Millis", JsonPrimitive(it)) }
                        summary.growthRatio?.let { put("pairedMedianGrowth", JsonPrimitive(it)) }
                    },
                )
            }),
            "forks" to JsonArray(run.forks.map { Json.parseToJsonElement(render(it)) }),
        ),
    ).toString()

    private fun median(values: List<Double>): Double =
        values.sorted()[values.size / 2]

    private fun nearestRank(values: List<Double>, percentile: Double): Double {
        require(percentile > 0.0 && percentile <= 1.0)
        val sorted = values.sorted()
        return sorted[ceil(percentile * sorted.size).toInt() - 1]
    }

    private fun runtimeJson(runtime: PerformanceRuntime): JsonObject = JsonObject(
        mapOf(
            "name" to JsonPrimitive(runtime.name),
            "version" to JsonPrimitive(runtime.version),
            "os" to JsonPrimitive(runtime.os),
            "arch" to JsonPrimitive(runtime.arch),
            "pid" to JsonPrimitive(runtime.pid),
            "maxHeapBytes" to JsonPrimitive(runtime.maxHeapBytes),
        ),
    )

    private fun sampleJson(sample: PerformanceScaleSample): JsonObject = JsonObject(
        mapOf(
            "scale" to JsonPrimitive(sample.scale),
            "elapsedMillis" to JsonArray(sample.elapsedMillis.map(::JsonPrimitive)),
            "cardinality" to JsonPrimitive(sample.cardinality),
            "supported" to JsonPrimitive(sample.supported),
            "outcome" to JsonPrimitive(sample.outcome),
        ),
    )

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$name is required")

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("$name must be an integer")

    private fun JsonObject.requiredLong(name: String): Long =
        get(name)?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("$name must be an integer")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        get(name)?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("$name must be a boolean")

    private fun JsonObject.requiredObject(name: String): JsonObject =
        get(name)?.let {
            try {
                it.jsonObject
            } catch (failure: Exception) {
                throw IllegalArgumentException("$name must be an object", failure)
            }
        } ?: throw IllegalArgumentException("$name is required")

    private fun JsonObject.requiredArray(name: String): JsonArray =
        get(name)?.let {
            try {
                it.jsonArray
            } catch (failure: Exception) {
                throw IllegalArgumentException("$name must be an array", failure)
            }
        } ?: throw IllegalArgumentException("$name is required")
}
