package io.github.mbackschat.a12.dm.interpreter.footprint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ln

internal enum class FootprintMetric {
    RETAINED_BYTES,
    ALLOCATED_BYTES,
}

internal data class FootprintCurve(
    val host: FootprintHost,
    val scenario: FootprintScenario,
    val metric: FootprintMetric,
    val medians: List<Double>,
    val spaceExponent: Double,
    val worstFourXStep: Double,
)

internal data class FootprintAggregateReport(
    val commit: String,
    val scales: List<Int>,
    val warmup: Int,
    val repetitions: Int,
    val requiredForksPerHost: Int,
    val forks: List<FootprintFork>,
    val curves: List<FootprintCurve>,
)

internal object FootprintAggregation {

    fun aggregate(forks: List<FootprintFork>, requiredForksPerHost: Int): FootprintAggregateReport {
        require(forks.isNotEmpty()) { "no footprint worker results were supplied" }
        val scales = forks.first().scales
        val validated = FootprintProtocol.validateForkSet(
            forks = forks,
            requiredForksPerHost = requiredForksPerHost,
            expectedScales = scales,
            expectedScenarios = FootprintScenario.entries,
        )
        require(forks.map { it.warmup }.distinct().size == 1) { "all forks must use the same warmup count" }
        require(forks.map { it.repetitions }.distinct().size == 1) {
            "all forks must use the same repetition count"
        }

        val curves = buildList {
            FootprintHost.entries.forEach { host ->
                FootprintScenario.entries.forEach { scenario ->
                    FootprintMetric.entries.forEach { metric ->
                        val medians = scales.map { scale ->
                            median(
                                validated.forks.asSequence()
                                    .filter { it.host == host }
                                    .flatMap { it.samples.asSequence() }
                                    .filter { it.scenario == scenario && it.scale == scale }
                                    .map { sample ->
                                        when (metric) {
                                            FootprintMetric.RETAINED_BYTES -> sample.retainedBytes
                                            FootprintMetric.ALLOCATED_BYTES -> sample.allocatedBytes
                                        }
                                    }
                                    .toList(),
                            )
                        }
                        add(
                            FootprintCurve(
                                host = host,
                                scenario = scenario,
                                metric = metric,
                                medians = medians,
                                spaceExponent = spaceExponent(scales, medians),
                                worstFourXStep = (0 until scales.size - 2)
                                    .maxOf { index -> medians[index + 2] / medians[index] },
                            ),
                        )
                    }
                }
            }
        }
        return FootprintAggregateReport(
            commit = forks.first().commit,
            scales = scales.toList(),
            warmup = forks.first().warmup,
            repetitions = forks.first().repetitions,
            requiredForksPerHost = requiredForksPerHost,
            forks = validated.forks,
            curves = curves,
        )
    }

    fun render(report: FootprintAggregateReport): String = JsonObject(
        mapOf(
            "protocolVersion" to JsonPrimitive(1),
            "informativeOnly" to JsonPrimitive(true),
            "commit" to JsonPrimitive(report.commit),
            "scales" to JsonArray(report.scales.map(::JsonPrimitive)),
            "warmup" to JsonPrimitive(report.warmup),
            "repetitions" to JsonPrimitive(report.repetitions),
            "requiredForksPerHost" to JsonPrimitive(report.requiredForksPerHost),
            "forks" to JsonArray(
                report.forks.map { fork -> Json.parseToJsonElement(FootprintProtocol.render(fork)) },
            ),
            "curves" to JsonArray(report.curves.map(::renderCurve)),
        ),
    ).toString()

    private fun renderCurve(curve: FootprintCurve): JsonObject = JsonObject(
        mapOf(
            "host" to JsonPrimitive(curve.host.name),
            "scenario" to JsonPrimitive(curve.scenario.id),
            "metric" to JsonPrimitive(curve.metric.name),
            "medians" to JsonArray(curve.medians.map(::JsonPrimitive)),
            "spaceExponent" to JsonPrimitive(curve.spaceExponent),
            "worstFourXStep" to JsonPrimitive(curve.worstFourXStep),
        ),
    )

    private fun median(values: List<Long>): Double {
        require(values.isNotEmpty()) { "cannot aggregate an empty sample set" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle].toDouble()
        } else {
            sorted[middle - 1] + (sorted[middle] - sorted[middle - 1]) / 2.0
        }
    }

    private fun spaceExponent(scales: List<Int>, medians: List<Double>): Double {
        val xs = scales.map { ln(it.toDouble()) }
        val ys = medians.map(::ln)
        val meanX = xs.average()
        val meanY = ys.average()
        val numerator = xs.indices.sumOf { index -> (xs[index] - meanX) * (ys[index] - meanY) }
        val denominator = xs.sumOf { value -> (value - meanX) * (value - meanX) }
        require(denominator > 0.0) { "footprint scales do not span a curve" }
        return numerator / denominator
    }
}
