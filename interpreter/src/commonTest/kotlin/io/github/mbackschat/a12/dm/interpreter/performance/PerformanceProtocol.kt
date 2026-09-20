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
    /** Measuring-thread CPU milliseconds for the same regions as [elapsedMillis], index for index. */
    val cpuMillis: List<Double>,
    /**
     * GC milliseconds for the same regions, index for index, or `null` where the carrier cannot report it.
     *
     * Present on the JVM and absent on Node, for the reason [PerformancePlatform.cumulativeGcMillis] gives. It
     * exists so a low [occupancy] can be attributed: collection the code itself caused, or a machine that was
     * busy. `null` is "not observable", never "no GC".
     */
    val gcMillis: List<Double>?,
    val cardinality: Int,
    val supported: Boolean,
    val outcome: String,
) {
    /**
     * Median share of this scale's regions that the measuring thread spent running, with **collection time
     * removed from the denominator** where the carrier reports it.
     *
     * Near 1.0 the thread owned its core and the elapsed time is the code's cost. Well below it the thread was
     * descheduled or waiting, and the elapsed time is partly the machine's — the confusion that let a
     * contaminated bundle arm read as a 24–41 % improvement (IG153).
     *
     * **Why GC leaves the denominator, and why the plain ratio had to go (measured 2026-09-20, idle machine).**
     * Collection runs on its own threads, so a pause inflates wall while leaving this numerator flat: a plain
     * `cpu / wall` therefore falls when the code ALLOCATES more, which makes it partly a *post-treatment*
     * quantity — the property `PerfEnvironmentQuality` cites for never filtering on GC at all. That is not a
     * theoretical worry, it **inverts the ranking**. One scenario, the heap the only variable, nothing else on
     * the box: at 1 g the plain ratio read 0.8745 and at 320 m it read **0.6868 — under the old 0.70 floor** —
     * while a genuinely contaminated capture (four busy cores, wall inflated 37.5 %) read 0.8302, comfortably
     * above it. The old bar would have refused the healthy capture and passed the contaminated one.
     *
     * With collection out of the denominator the same two idle arms read **0.9972** and **0.9962**, so a 3.6×
     * change in GC time (27 → 98 ms per region) moves this quantity by 0.001. That is what makes it a contention
     * signal rather than an allocation one.
     *
     * A region the carrier cannot report GC for keeps the plain ratio, which is right for Node: its numerator is
     * whole-process CPU and already counts V8's collector, so allocation never depressed it.
     */
    fun occupancy(): Double {
        require(elapsedMillis.size == cpuMillis.size) { "cpu and wall sample counts differ" }
        val gc = gcMillis
        val ratios = elapsedMillis.indices
            .map { index ->
                val wall = elapsedMillis[index] - (gc?.get(index) ?: 0.0)
                // GC elapsed time is an UPPER bound on the pause charged to this thread, so the adjusted
                // denominator can reach zero. A region wholly accounted for by collection carries no evidence
                // that the machine was busy, and a contention floor must not refuse it as though it did.
                if (wall <= 0.0) 1.0 else cpuMillis[index] / wall
            }
            .sorted()
        return ratios[ratios.size / 2]
    }
}

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

    /**
     * 3 since 2026-09-19, when a JVM sample gained the GC time behind its occupancy; 2 added occupancy itself.
     *
     * A clean break rather than an optional field, both times: a record without occupancy cannot be judged for
     * contamination and one without GC cannot have a low occupancy ATTRIBUTED, so silently admitting either
     * reinstates a blind spot — and the raws are ephemeral build outputs, so nothing committed needs reading
     * back at an older version. The per-sample series is separately nullable because a *carrier* may be unable
     * to report GC; that is a different statement from a record predating the field.
     */
    private const val VERSION = 3

    /**
     * The floor a process's median [PerformanceScaleSample.occupancy] must clear — **per carrier, because it is
     * not the same quantity on both**, and measured rather than chosen.
     *
     * **What this bar claims, stated narrowly because the measurement will not support more.** It asks *did
     * this region get a core*, not *was the machine quiet*. Measured on an idle machine 2026-09-20 against the
     * same scenario with four of eight cores busy: wall time inflated **+37.5 %** while the corrected ratio
     * moved only **−4.0 %**, because stalled cycles are charged to the measuring thread and so land in the
     * numerator too (+30.5 %). Two of that arm's three samples sit inside the idle band. So moderate contention
     * is NOT detectable here and no floor on this quantity can catch it; what is detectable is starvation, and
     * cleanly — two-times core oversubscription measured **0.313 – 0.348** against a healthy **0.833 – 1.000**.
     * Covering the gap needs a different instrument, tracked as IG154.
     *
     * **JVM — floor 0.75.** The numerator is the MEASURING THREAD's CPU and the denominator excludes collection,
     * so a healthy warm region sits at essentially 1.0: **6 warm-throughput samples across the two heaps read
     * 0.9924 – 1.0002** (the upper value above 1.0 because collector elapsed time is an upper bound on the pause
     * this thread actually paid). The binding constraint is not throughput but a **cold first answer**: the first
     * fork of a bundle's first scenario measured **0.8328** with no collection at all, the thread blocked on
     * class loading rather than starved, while its sibling forks read 0.9944 and 0.9921. A floor must admit that
     * sample, so it sits ~10 % below it and still 2.2× above the starved band.
     *
     * **Node — floor 0.85, unchanged and re-confirmed.** The numerator is `process.cpuUsage()`, which counts
     * EVERY thread — the libuv pool and V8's background GC and compiler threads included — so a healthy region
     * sits *above* 1.0: 15 samples measured **1.041 – 2.250, median 1.154** (2026-09-20), against 1.041 – 2.355
     * a day earlier. Node reports no GC, so its ratio is uncorrected; it never needed the correction, because a
     * collector in the numerator cannot depress it. An early draft asserted Node was single-threaded and could
     * share the JVM's floor; the first measurement refuted that, and a shared floor would have been either
     * blind on Node or permanently red on the JVM.
     */
    internal fun minOccupancy(host: PerformanceHost): Double = when (host) {
        PerformanceHost.JVM -> 0.75
        PerformanceHost.NODE -> 0.85
    }
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
            // Index for index, because occupancy divides one by the other. A pair of arrays that merely happen
            // to be the same length is the weaker property, but a length mismatch is the only misalignment a
            // record can express, and it is the one a partly-written record produces.
            require(sample.cpuMillis.size == sample.elapsedMillis.size) {
                "${fork.scenario.id}/${sample.scale}: ${sample.cpuMillis.size} cpu samples against " +
                    "${sample.elapsedMillis.size} wall samples, so occupancy cannot be computed"
            }
            require(sample.cpuMillis.all { it.isFinite() && it >= 0.0 }) {
                "${fork.scenario.id}/${sample.scale}: cpu times must be finite and non-negative"
            }
            // A carrier may report no GC at all, which is absent rather than empty. What it may not do is report
            // a series that does not line up with the regions: the whole point of recording GC is to attribute
            // region i's low occupancy, and a misaligned series would attribute a neighbour's collection to it.
            sample.gcMillis?.let { gc ->
                require(gc.size == sample.elapsedMillis.size) {
                    "${fork.scenario.id}/${sample.scale}: ${gc.size} gc samples against " +
                        "${sample.elapsedMillis.size} wall samples, so a low occupancy could not be attributed"
                }
                require(gc.all { it.isFinite() && it >= 0.0 }) {
                    "${fork.scenario.id}/${sample.scale}: gc times must be finite and non-negative"
                }
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
        // Refused HERE rather than in validateFork, deliberately: a fork is a legitimate record of what a
        // contended process measured, and throwing it away would destroy the evidence. What must not happen is
        // that such a record becomes a COMPARISON — this function is where per-process numbers turn into a
        // report someone reads as a delta, so this is where a descheduled process has to stop (IG153).
        forks.forEach { fork ->
            val floor = minOccupancy(fork.host)
            fork.samples.forEach { sample ->
                val occupancy = sample.occupancy()
                require(occupancy >= floor) {
                    "${fork.scenario.id}/${sample.scale} in ${fork.invocationId}: ${fork.host} occupancy " +
                        "${(occupancy * 100).toInt()}% is below the ${(floor * 100).toInt()}% floor — the " +
                        "region's elapsed time was not matched by CPU time, so these timings are not a clean " +
                        "read of the code. TWO causes produce this and the ratio cannot tell them apart: " +
                        "external CPU contention, and GC pauses from the code's OWN allocation, since GC runs " +
                        "off the measuring thread. Re-measure on an idle machine — if it clears, it was " +
                        "contention; if it reproduces, suspect allocation rather than the box"
                }
            }
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
                    cpuMillis = sample.requiredArray("cpuMillis").mapIndexed { index, timing ->
                        timing.jsonPrimitive.doubleOrNull
                            ?: throw IllegalArgumentException("cpuMillis[$index] must be a number")
                    },
                    gcMillis = (sample["gcMillis"] as? JsonArray)?.mapIndexed { index, timing ->
                        timing.jsonPrimitive.doubleOrNull
                            ?: throw IllegalArgumentException("gcMillis[$index] must be a number")
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
        buildMap {
            put("scale", JsonPrimitive(sample.scale))
            put("elapsedMillis", JsonArray(sample.elapsedMillis.map(::JsonPrimitive)))
            put("cpuMillis", JsonArray(sample.cpuMillis.map(::JsonPrimitive)))
            // Omitted rather than written as null when the carrier cannot report: a key that is absent and a key
            // whose value is `null` both parse back to absent, and the shorter form keeps the record honest
            // about having nothing to say.
            sample.gcMillis?.let { put("gcMillis", JsonArray(it.map(::JsonPrimitive))) }
            put("cardinality", JsonPrimitive(sample.cardinality))
            put("supported", JsonPrimitive(sample.supported))
            put("outcome", JsonPrimitive(sample.outcome))
        },
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
