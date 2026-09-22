package io.github.mbackschat.a12.dm.interpreter.footprint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal enum class FootprintHost {
    JVM,
    NODE,
}

internal enum class FootprintScenario(
    val id: String,
    val expectedOutcome: String,
    val generatorIdentity: String,
) {
    SIMPLE_VALIDATION(
        "simple-validation",
        "diagnostics=SIMPLE_REQUIRED",
        "row-values:v1(value=present)",
    ),
    EQUALITY_CORRELATION(
        "equality-correlation",
        "diagnostics=EQUAL_PAIR",
        "equal-key-rows:v1(key=same-key)",
    ),
    COMPUTE_OVERLAY(
        "compute-overlay",
        "computed=VALUE",
        "compute-rows:v1(key=second-row-kb;source=10)",
    );

    fun expectedCardinality(scale: Int): Int = when (this) {
        SIMPLE_VALIDATION, EQUALITY_CORRELATION -> scale
        COMPUTE_OVERLAY -> scale * 3
    }

    companion object {
        fun fromId(id: String): FootprintScenario =
            entries.firstOrNull { it.id == id } ?: invalid("samples[].scenario", "unknown value '$id'")
    }
}

internal data class FootprintRuntime(
    val name: String,
    val version: String,
    val os: String,
    val arch: String,
    val pid: Long,
    val maxHeapBytes: Long,
    val collector: String,
)

internal data class FootprintGc(
    val explicit: Boolean,
    val viable: Boolean,
    val method: String,
)

internal data class FootprintCacheEntries(
    val document: Int,
    val parsedModel: Int,
    val overlayPeak: Int,
    val overlayAfterRelease: Int,
    val afterOwnerRelease: Int,
)

internal data class FootprintSample(
    val scenario: FootprintScenario,
    val scenarioIdentity: String,
    val scale: Int,
    val retainedBytes: Long,
    val allocatedBytes: Long,
    val cacheEntries: FootprintCacheEntries,
    val ownersReleased: Boolean,
    val supported: Boolean,
    val semanticOutcome: String,
    val resultCardinality: Int,
)

internal data class FootprintFork(
    val protocolVersion: Int,
    val commit: String,
    val host: FootprintHost,
    val invocationId: String,
    val runtime: FootprintRuntime,
    val gc: FootprintGc,
    val allocationMethod: String,
    val scales: List<Int>,
    val warmup: Int,
    val repetitions: Int,
    val samples: List<FootprintSample>,
)

internal data class ValidatedFootprintRun(
    val forks: List<FootprintFork>,
    val scales: List<Int>,
    val scenarios: List<FootprintScenario>,
)

internal object FootprintProtocol {

    private const val VERSION = 1
    private val SHA_256 = Regex("[0-9a-f]{64}")

    fun parse(raw: String): FootprintFork {
        val root = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (failure: Exception) {
            throw IllegalArgumentException("footprint result is not a JSON object", failure)
        }
        val runtime = root.requiredObject("runtime", "runtime")
        val gc = root.requiredObject("gc", "gc")
        return FootprintFork(
            protocolVersion = root.requiredInt("protocolVersion", "protocolVersion"),
            commit = root.requiredString("commit", "commit"),
            host = parseHost(root.requiredString("host", "host")),
            invocationId = root.requiredString("invocationId", "invocationId"),
            runtime = FootprintRuntime(
                name = runtime.requiredString("name", "runtime.name"),
                version = runtime.requiredString("version", "runtime.version"),
                os = runtime.requiredString("os", "runtime.os"),
                arch = runtime.requiredString("arch", "runtime.arch"),
                pid = runtime.requiredLong("pid", "runtime.pid"),
                maxHeapBytes = runtime.requiredLong("maxHeapBytes", "runtime.maxHeapBytes"),
                collector = runtime.requiredString("collector", "runtime.collector"),
            ),
            gc = FootprintGc(
                explicit = gc.requiredBoolean("explicit", "gc.explicit"),
                viable = gc.requiredBoolean("viable", "gc.viable"),
                method = gc.requiredString("method", "gc.method"),
            ),
            allocationMethod = root.requiredString("allocationMethod", "allocationMethod"),
            scales = root.requiredArray("scales", "scales").mapIndexed { index, element ->
                element.jsonPrimitive.intOrNull ?: invalid("scales[$index]", "expected an integer")
            },
            warmup = root.requiredInt("warmup", "warmup"),
            repetitions = root.requiredInt("repetitions", "repetitions"),
            samples = root.requiredArray("samples", "samples").mapIndexed(::parseSample),
        )
    }

    fun validateForkSet(
        forks: List<FootprintFork>,
        requiredForksPerHost: Int,
        expectedScales: List<Int>,
        expectedScenarios: List<FootprintScenario>,
    ): ValidatedFootprintRun {
        require(requiredForksPerHost > 0) { "requiredForksPerHost must be positive" }
        require(expectedScales.size == 4 && expectedScales.first() > 0 &&
            expectedScales.zipWithNext().all { (before, after) -> after == before * 2 }) {
            "expectedScales must be exactly N, 2N, 4N, 8N"
        }
        require(expectedScenarios.isNotEmpty() && expectedScenarios.distinct() == expectedScenarios) {
            "expectedScenarios must be non-empty and unique"
        }
        FootprintHost.entries.forEach { host ->
            require(forks.count { it.host == host } >= requiredForksPerHost) {
                "$host has fewer than $requiredForksPerHost independently invoked forks"
            }
        }
        require(forks.map { it.host to it.runtime.pid }.distinct().size == forks.size) {
            "each host fork must have a distinct PID"
        }
        require(forks.map { it.invocationId }.distinct().size == forks.size) {
            "each fork must have a distinct invocationId"
        }
        require(forks.map { it.commit }.distinct().size == 1) {
            "all forks must measure the same commit"
        }

        forks.forEach { fork -> validateSingleFork(fork, expectedScales, expectedScenarios) }
        expectedScenarios.forEach { scenario ->
            val identities = forks.flatMap { fork ->
                fork.samples.filter { it.scenario == scenario }.map { it.scenarioIdentity }
            }.distinct()
            require(identities.size == 1) {
                "${scenario.id} must have one content fingerprint across every host/fork: $identities"
            }
        }
        return ValidatedFootprintRun(forks.toList(), expectedScales.toList(), expectedScenarios.toList())
    }

    fun validateSingleFork(
        fork: FootprintFork,
        expectedScales: List<Int>,
        expectedScenarios: List<FootprintScenario>,
    ): FootprintFork {
        require(fork.protocolVersion == VERSION) { "unsupported protocolVersion ${fork.protocolVersion}" }
        require(fork.commit.matches(Regex("[0-9a-f]{8,64}"))) { "commit must be a hexadecimal git identity" }
        require(fork.invocationId.isNotBlank()) { "invocationId must not be blank" }
        require(fork.runtime.name.isNotBlank()) { "runtime.name must not be blank" }
        require(fork.runtime.version.isNotBlank()) { "runtime.version must not be blank" }
        require(fork.runtime.os.isNotBlank()) { "runtime.os must not be blank" }
        require(fork.runtime.arch.isNotBlank()) { "runtime.arch must not be blank" }
        require(fork.runtime.pid > 0) { "runtime.pid must be positive" }
        require(fork.runtime.maxHeapBytes > 0) { "runtime.maxHeapBytes must be positive" }
        require(fork.runtime.collector.isNotBlank()) { "runtime.collector must not be blank" }
        require(fork.gc.explicit) { "gc.explicit must be true" }
        require(fork.gc.viable) { "gc.viable must be true" }
        require(fork.gc.method.isNotBlank()) { "gc.method must not be blank" }
        require(fork.allocationMethod.isNotBlank()) { "allocationMethod must not be blank" }
        require(fork.warmup > 0) { "warmup must be positive" }
        require(fork.repetitions > 0) { "repetitions must be positive" }
        require(fork.scales == expectedScales) {
            "${fork.host}/${fork.invocationId} scales ${fork.scales} != $expectedScales"
        }

        val expectedKeys = expectedScenarios.flatMap { scenario ->
            expectedScales.map { scale -> scenario to scale }
        }.toSet()
        val actualKeys = fork.samples.map { it.scenario to it.scale }
        require(actualKeys.size == actualKeys.distinct().size) {
            "${fork.host}/${fork.invocationId} contains duplicate scenario/scale samples"
        }
        require(actualKeys.toSet() == expectedKeys) {
            "${fork.host}/${fork.invocationId} samples are incomplete: actual=${actualKeys.toSet()} expected=$expectedKeys"
        }
        fork.samples.forEach(::validateSample)
        return fork
    }

    fun render(fork: FootprintFork): String = JsonObject(
        mapOf(
            "protocolVersion" to JsonPrimitive(fork.protocolVersion),
            "commit" to JsonPrimitive(fork.commit),
            "host" to JsonPrimitive(fork.host.name),
            "invocationId" to JsonPrimitive(fork.invocationId),
            "runtime" to JsonObject(
                mapOf(
                    "name" to JsonPrimitive(fork.runtime.name),
                    "version" to JsonPrimitive(fork.runtime.version),
                    "os" to JsonPrimitive(fork.runtime.os),
                    "arch" to JsonPrimitive(fork.runtime.arch),
                    "pid" to JsonPrimitive(fork.runtime.pid),
                    "maxHeapBytes" to JsonPrimitive(fork.runtime.maxHeapBytes),
                    "collector" to JsonPrimitive(fork.runtime.collector),
                ),
            ),
            "gc" to JsonObject(
                mapOf(
                    "explicit" to JsonPrimitive(fork.gc.explicit),
                    "viable" to JsonPrimitive(fork.gc.viable),
                    "method" to JsonPrimitive(fork.gc.method),
                ),
            ),
            "allocationMethod" to JsonPrimitive(fork.allocationMethod),
            "scales" to JsonArray(fork.scales.map(::JsonPrimitive)),
            "warmup" to JsonPrimitive(fork.warmup),
            "repetitions" to JsonPrimitive(fork.repetitions),
            "samples" to JsonArray(fork.samples.map(::renderSample)),
        ),
    ).toString()

    private fun renderSample(sample: FootprintSample): JsonObject = JsonObject(
        mapOf(
            "scenario" to JsonPrimitive(sample.scenario.id),
            "scenarioIdentity" to JsonPrimitive(sample.scenarioIdentity),
            "scale" to JsonPrimitive(sample.scale),
            "retainedBytes" to JsonPrimitive(sample.retainedBytes),
            "allocatedBytes" to JsonPrimitive(sample.allocatedBytes),
            "cacheEntries" to JsonObject(
                mapOf(
                    "document" to JsonPrimitive(sample.cacheEntries.document),
                    "parsedModel" to JsonPrimitive(sample.cacheEntries.parsedModel),
                    "overlayPeak" to JsonPrimitive(sample.cacheEntries.overlayPeak),
                    "overlayAfterRelease" to JsonPrimitive(sample.cacheEntries.overlayAfterRelease),
                    "afterOwnerRelease" to JsonPrimitive(sample.cacheEntries.afterOwnerRelease),
                ),
            ),
            "ownersReleased" to JsonPrimitive(sample.ownersReleased),
            "supported" to JsonPrimitive(sample.supported),
            "semanticOutcome" to JsonPrimitive(sample.semanticOutcome),
            "resultCardinality" to JsonPrimitive(sample.resultCardinality),
        ),
    )

    private fun validateSample(sample: FootprintSample) {
        require(SHA_256.matches(sample.scenarioIdentity)) {
            "${sample.scenario.id} scenarioIdentity must be a SHA-256 content fingerprint"
        }
        require(sample.retainedBytes > 0) {
            "${sample.scenario.id}/${sample.scale} retainedBytes must be positive; noisy samples are rejected, not clamped"
        }
        require(sample.allocatedBytes > 0) {
            "${sample.scenario.id}/${sample.scale} allocatedBytes must be positive; invalid samples are rejected"
        }
        require(sample.cacheEntries.document > 0) {
            "${sample.scenario.id}/${sample.scale} cacheEntries.document must prove the cache was exercised"
        }
        require(sample.cacheEntries.parsedModel > 0) {
            "${sample.scenario.id}/${sample.scale} cacheEntries.parsedModel must prove the cache was exercised"
        }
        require(sample.cacheEntries.overlayPeak >= 0) {
            "${sample.scenario.id}/${sample.scale} cacheEntries.overlayPeak must not be negative"
        }
        if (sample.scenario == FootprintScenario.COMPUTE_OVERLAY) {
            require(sample.cacheEntries.overlayPeak > 0) {
                "compute-overlay must exercise cacheEntries.overlayPeak"
            }
        }
        require(sample.cacheEntries.overlayAfterRelease == 0) {
            "${sample.scenario.id}/${sample.scale} cacheEntries.overlayAfterRelease must be zero"
        }
        require(sample.cacheEntries.afterOwnerRelease == 0) {
            "${sample.scenario.id}/${sample.scale} cacheEntries.afterOwnerRelease must be zero"
        }
        require(sample.ownersReleased) { "${sample.scenario.id}/${sample.scale} leaked a discarded owner" }
        require(sample.supported) { "${sample.scenario.id}/${sample.scale} reported unsupported semantics" }
        require(sample.semanticOutcome == sample.scenario.expectedOutcome) {
            "${sample.scenario.id}/${sample.scale} semantic outcome '${sample.semanticOutcome}' != " +
                "'${sample.scenario.expectedOutcome}'"
        }
        require(sample.resultCardinality == sample.scenario.expectedCardinality(sample.scale)) {
            "${sample.scenario.id}/${sample.scale} result cardinality ${sample.resultCardinality} != " +
                sample.scenario.expectedCardinality(sample.scale)
        }
    }

    private fun parseSample(index: Int, element: JsonElement): FootprintSample {
        val path = "samples[$index]"
        val sample = try {
            element.jsonObject
        } catch (failure: Exception) {
            throw IllegalArgumentException("$path must be an object", failure)
        }
        val cache = sample.requiredObject("cacheEntries", "$path.cacheEntries")
        return FootprintSample(
            scenario = FootprintScenario.fromId(sample.requiredString("scenario", "$path.scenario")),
            scenarioIdentity = sample.requiredString("scenarioIdentity", "$path.scenarioIdentity"),
            scale = sample.requiredInt("scale", "$path.scale"),
            retainedBytes = sample.requiredLong("retainedBytes", "$path.retainedBytes"),
            allocatedBytes = sample.requiredLong("allocatedBytes", "$path.allocatedBytes"),
            cacheEntries = FootprintCacheEntries(
                document = cache.requiredInt("document", "$path.cacheEntries.document"),
                parsedModel = cache.requiredInt("parsedModel", "$path.cacheEntries.parsedModel"),
                overlayPeak = cache.requiredInt("overlayPeak", "$path.cacheEntries.overlayPeak"),
                overlayAfterRelease = cache.requiredInt(
                    "overlayAfterRelease",
                    "$path.cacheEntries.overlayAfterRelease",
                ),
                afterOwnerRelease = cache.requiredInt(
                    "afterOwnerRelease",
                    "$path.cacheEntries.afterOwnerRelease",
                ),
            ),
            ownersReleased = sample.requiredBoolean("ownersReleased", "$path.ownersReleased"),
            supported = sample.requiredBoolean("supported", "$path.supported"),
            semanticOutcome = sample.requiredString("semanticOutcome", "$path.semanticOutcome"),
            resultCardinality = sample.requiredInt("resultCardinality", "$path.resultCardinality"),
        )
    }

    private fun parseHost(value: String): FootprintHost =
        FootprintHost.entries.firstOrNull { it.name == value } ?: invalid("host", "unknown value '$value'")
}

private fun JsonObject.requiredObject(name: String, path: String): JsonObject =
    try {
        get(name)?.jsonObject ?: invalid(path, "is required")
    } catch (failure: IllegalArgumentException) {
        throw failure
    } catch (failure: Exception) {
        throw IllegalArgumentException("$path must be an object", failure)
    }

private fun JsonObject.requiredArray(name: String, path: String): List<JsonElement> =
    try {
        get(name)?.jsonArray ?: invalid(path, "is required")
    } catch (failure: IllegalArgumentException) {
        throw failure
    } catch (failure: Exception) {
        throw IllegalArgumentException("$path must be an array", failure)
    }

private fun JsonObject.requiredString(name: String, path: String): String =
    get(name)?.jsonPrimitive?.takeIf { it.isString }?.content ?: invalid(path, "must be a string")

private fun JsonObject.requiredInt(name: String, path: String): Int =
    get(name)?.jsonPrimitive?.intOrNull ?: invalid(path, "must be an integer")

private fun JsonObject.requiredLong(name: String, path: String): Long =
    get(name)?.jsonPrimitive?.longOrNull ?: invalid(path, "must be an integer")

private fun JsonObject.requiredBoolean(name: String, path: String): Boolean =
    get(name)?.jsonPrimitive?.booleanOrNull ?: invalid(path, "must be a boolean")

private fun invalid(path: String, message: String): Nothing =
    throw IllegalArgumentException("$path $message")
