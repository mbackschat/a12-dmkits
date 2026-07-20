package io.github.mbackschat.a12.dm.interpreter.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PerformanceProtocolTest {

    @Test
    fun firstAnswerUsesIndependentProcessesAndNearestRankP95() {
        val scenario = PerformanceScenario.NODE_10K_ROWS_FIRST_ANSWER
        val forks = (1..20).map { index ->
            fork(
                scenario,
                pid = index.toLong(),
                invocation = "node-$index",
                timings = listOf(index.toDouble()),
            )
        }

        val run = PerformanceProtocol.aggregate(forks, 20, listOf(scenario))
        val summary = run.summaries.single()

        assertEquals(11.0, summary.medianMillis)
        assertEquals(19.0, summary.p95Millis)
        assertEquals(20, summary.sampleCount)
    }

    @Test
    fun pairedThroughputUsesMedianOfWithinPairRatios() {
        val scenario = PerformanceScenario.NODE_B1_THROUGHPUT
        val fork = fork(
            scenario,
            pid = 1,
            invocation = "node-throughput",
            timings = listOf(1.0, 100.0, 10.0),
            largeTimings = listOf(4.0, 200.0, 50.0),
            warmup = 3,
        )

        val summary = PerformanceProtocol.aggregate(listOf(fork), 1, listOf(scenario)).summaries.single()

        assertEquals(50.0, summary.medianMillis)
        assertEquals(4.0, summary.growthRatio)
    }

    @Test
    fun reusedProcessCannotMasqueradeAsIndependentSamples() {
        val scenario = PerformanceScenario.NODE_10K_ROWS_FIRST_ANSWER
        val first = fork(scenario, 1, "one", listOf(1.0))
        val second = fork(scenario, 1, "two", listOf(2.0))

        assertFailsWith<IllegalArgumentException> {
            PerformanceProtocol.aggregate(listOf(first, second), 2, listOf(scenario))
        }
    }

    @Test
    fun protocolRoundTripsAValidatedFork() {
        val original = fork(
            PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT,
            7,
            "jvm-throughput",
            listOf(2.0, 1.0, 3.0),
            warmup = 2,
        )

        assertEquals(original, PerformanceProtocol.parse(PerformanceProtocol.render(original)))
    }

    @Test
    fun reportsExposeTheVersionedScenarioLabel() {
        val scenario = PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT
        val fork = fork(scenario, 7, "jvm-throughput", listOf(2.0, 1.0, 3.0), warmup = 2)
        val forkJson = Json.parseToJsonElement(PerformanceProtocol.render(fork)).jsonObject
        val runJson = Json.parseToJsonElement(
            PerformanceProtocol.render(PerformanceProtocol.aggregate(listOf(fork), 1, listOf(scenario))),
        ).jsonObject

        assertEquals("${scenario.id}@${scenario.version}", forkJson["identityLabel"]?.jsonPrimitive?.content)
        assertEquals(
            "${scenario.id}@${scenario.version}",
            runJson["summaries"]?.jsonArray?.single()?.jsonObject?.get("identityLabel")?.jsonPrimitive?.content,
        )
    }

    private fun fork(
        scenario: PerformanceScenario,
        pid: Long,
        invocation: String,
        timings: List<Double>,
        largeTimings: List<Double>? = null,
        warmup: Int = 0,
    ): PerformanceFork {
        val timingSets = if (largeTimings == null) {
            listOf(timings)
        } else {
            listOf(timings, largeTimings)
        }
        return PerformanceFork(
            protocolVersion = 1,
            commit = "12345678",
            runner = "pinned-test-runner",
            invocationId = invocation,
            host = scenario.host,
            runtime = PerformanceRuntime("runtime", "1", "os", "arch", pid, 1),
            scenario = scenario,
            scenarioIdentity = "a".repeat(64),
            warmup = warmup,
            samples = scenario.scales.zip(timingSets).map { (scale, values) ->
                PerformanceScaleSample(scale, values, 1, true, "codes=X")
            },
        )
    }
}
