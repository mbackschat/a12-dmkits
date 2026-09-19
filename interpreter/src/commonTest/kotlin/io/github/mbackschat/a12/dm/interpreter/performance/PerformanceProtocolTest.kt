package io.github.mbackschat.a12.dm.interpreter.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    /**
     * A warm-throughput row is a median across fresh processes, exactly like a first-answer row.
     *
     * The protocol refused a second throughput process until 2026-09-19, which is why the only timed
     * quantity whose run-to-run variance is small enough to carry NFR #1's 5 % bar (IG152) could not be
     * sampled more than once per invocation. One process is a sample; a bar needs a statistic.
     */
    @Test
    fun warmThroughputMediansAcrossFreshProcesses() {
        val scenario = PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT
        val forks = listOf(
            fork(scenario, 1, "thr-1", listOf(10.0, 20.0, 30.0), warmup = 2),
            fork(scenario, 2, "thr-2", listOf(30.0, 40.0, 50.0), warmup = 2),
            fork(scenario, 3, "thr-3", listOf(20.0, 30.0, 40.0), warmup = 2),
        )

        val summary = PerformanceProtocol.aggregate(forks, 3, listOf(scenario)).summaries.single()

        assertEquals(30.0, summary.medianMillis)
        assertEquals(3, summary.sampleCount)
    }

    @Test
    fun pairedThroughputGrowthIsTheMedianAcrossFreshProcesses() {
        val scenario = PerformanceScenario.NODE_B1_THROUGHPUT
        val forks = listOf(
            fork(scenario, 1, "n-1", listOf(1.0, 1.0, 1.0), listOf(2.0, 2.0, 2.0), warmup = 3),
            fork(scenario, 2, "n-2", listOf(1.0, 1.0, 1.0), listOf(4.0, 4.0, 4.0), warmup = 3),
            fork(scenario, 3, "n-3", listOf(1.0, 1.0, 1.0), listOf(8.0, 8.0, 8.0), warmup = 3),
        )

        val summary = PerformanceProtocol.aggregate(forks, 3, listOf(scenario)).summaries.single()

        assertEquals(4.0, summary.medianMillis)
        assertEquals(4.0, summary.growthRatio)
    }

    /** The required count governs BOTH modes, so a throughput row cannot pass on fewer processes than asked for. */
    @Test
    fun warmThroughputEnforcesTheRequiredProcessCount() {
        val scenario = PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT
        val forks = listOf(fork(scenario, 1, "thr-1", listOf(10.0, 20.0, 30.0), warmup = 2))

        assertFailsWith<IllegalArgumentException> {
            PerformanceProtocol.aggregate(forks, 5, listOf(scenario))
        }
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

    /**
     * A process that spent its elapsed time waiting cannot become a comparison.
     *
     * This is IG153's guard. Two bundle invocations sixteen seconds apart once disagreed by 35–60 % on every
     * JVM lane and the report presented the difference as a 24–41 % improvement, because no field distinguished
     * "slower code" from "descheduled process". Occupancy does, and the refusal sits in `aggregate` rather than
     * `validateFork` so the contended record survives as evidence while never reaching a report.
     */
    @Test
    fun aStarvedProcessIsRefusedOnBothCarriers() {
        listOf(
            PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT,
            PerformanceScenario.NODE_B1_THROUGHPUT,
        ).forEach { scenario ->
            // A tenth of the wall time spent running, which is far below either carrier's healthy baseline.
            val starved = scenario.scales.map { listOf(100.0, 100.0, 100.0) }
            val fork = forkWithCpu(scenario, starved, starved.map { listOf(10.0, 10.0, 10.0) })

            val failure = assertFailsWith<IllegalArgumentException> {
                PerformanceProtocol.aggregate(listOf(fork), 1, listOf(scenario))
            }

            assertTrue(
                failure.message!!.contains("occupancy"),
                "${scenario.host}: the refusal must name occupancy, so a reader re-measures instead of hunting " +
                    "a regression; was: ${failure.message}",
            )
            // The ratio cannot separate external contention from the code's own GC pauses, so the message must
            // offer BOTH — a refusal that blames only the machine sends a reader to re-measure a reproducible
            // allocation regression. Locked because guidance is the first thing a later edit drops.
            listOf("contention", "GC").forEach { cause ->
                assertTrue(
                    failure.message!!.contains(cause),
                    "${scenario.host}: the refusal must name $cause as a candidate cause, since occupancy " +
                        "cannot discriminate the two; was: ${failure.message}",
                )
            }
        }
    }

    /**
     * The vacuity control, and the only thing that stops a floor being decorative.
     *
     * A floor of zero would satisfy every assertion above while refusing nothing — which is precisely how the
     * bundle came to carry no contamination check at all. Each carrier's floor is asserted to sit inside the
     * band where it can bite: above total starvation, and below that carrier's own measured idle minimum. The
     * two minima differ by more than the floors do, which is the whole reason the floor is keyed by host.
     */
    @Test
    fun eachCarriersOccupancyFloorSitsWhereItCanFire() {
        IDLE_MINIMUM.forEach { (host, idleMinimum) ->
            val floor = PerformanceProtocol.minOccupancy(host)
            assertTrue(
                floor > 0.0,
                "$host: a zero occupancy floor refuses nothing, so the contamination check is decorative",
            )
            assertTrue(
                floor < idleMinimum,
                "$host: the floor ($floor) must sit below the lowest occupancy measured on an idle machine " +
                    "($idleMinimum), or it fires on healthy runs and gets removed",
            )
        }
    }

    /** A healthy capture is not refused — the other half of the bar, without which the floor could be 10.0. */
    @Test
    fun anIdleMachinesMeasuredOccupancyIsAccepted() {
        IDLE_MINIMUM.forEach { (host, idleMinimum) ->
            val scenario = PerformanceScenario.entries.first { it.host == host && it.mode == PerformanceMode.WARM_THROUGHPUT }
            val wall = scenario.scales.map { listOf(100.0, 100.0, 100.0) }
            val cpu = scenario.scales.map { List(3) { 100.0 * idleMinimum } }

            PerformanceProtocol.aggregate(listOf(forkWithCpu(scenario, wall, cpu)), 1, listOf(scenario))
        }
    }

    @Test
    fun occupancyIsTheMedianRatioAcrossAScalesRegions() {
        val sample = PerformanceScaleSample(
            scale = 1,
            elapsedMillis = listOf(100.0, 100.0, 100.0),
            cpuMillis = listOf(10.0, 90.0, 50.0),
            cardinality = 1,
            supported = true,
            outcome = "codes=X",
        )

        assertEquals(
            0.5, sample.occupancy(),
            "occupancy must be the MEDIAN per-region ratio {0.1, 0.9, 0.5}, so one descheduled region does not " +
                "condemn a process and one healthy region does not excuse it",
        )
    }

    private fun fork(
        scenario: PerformanceScenario,
        pid: Long,
        invocation: String,
        timings: List<Double>,
        largeTimings: List<Double>? = null,
        warmup: Int = 0,
        cpu: List<Double>? = null,
    ): PerformanceFork {
        val timingSets = if (largeTimings == null) {
            listOf(timings)
        } else {
            listOf(timings, largeTimings)
        }
        return PerformanceFork(
            protocolVersion = 2,
            commit = "12345678",
            runner = "pinned-test-runner",
            invocationId = invocation,
            host = scenario.host,
            runtime = PerformanceRuntime("runtime", "1", "os", "arch", pid, 1),
            scenario = scenario,
            scenarioIdentity = "a".repeat(64),
            warmup = warmup,
            samples = scenario.scales.zip(timingSets).map { (scale, values) ->
                // Full occupancy unless a test asks otherwise: every assertion here is about aggregation, and a
                // fixture that quietly sat near the floor would make those assertions fail for the wrong reason.
                PerformanceScaleSample(scale, values, cpu ?: values, 1, true, "codes=X")
            },
        )
    }

    /** A fork with explicit per-scale wall and cpu series, for the occupancy bar's two directions. */
    private fun forkWithCpu(
        scenario: PerformanceScenario,
        wall: List<List<Double>>,
        cpu: List<List<Double>>,
    ): PerformanceFork = PerformanceFork(
        protocolVersion = 2,
        commit = "12345678",
        runner = "pinned-test-runner",
        invocationId = "occupancy",
        host = scenario.host,
        runtime = PerformanceRuntime("runtime", "1", "os", "arch", 1, 1),
        scenario = scenario,
        scenarioIdentity = "a".repeat(64),
        warmup = 3,
        samples = scenario.scales.indices.map { index ->
            PerformanceScaleSample(scenario.scales[index], wall[index], cpu[index], 1, true, "codes=X")
        },
    )

    private companion object {
        /**
         * The lowest median occupancy measured per carrier on an IDLE machine — 12 JVM and 15 Node
         * scale-samples of one bundle invocation, 2026-09-19. The floors must sit below these, and a healthy
         * capture at exactly these values must be accepted; both directions are asserted above.
         *
         * They differ by more than either floor's margin, which is the measured reason the floor is keyed by
         * host: the JVM numerator is the measuring thread's CPU, Node's is every thread's.
         */
        val IDLE_MINIMUM = mapOf(
            PerformanceHost.JVM to 0.8588,
            PerformanceHost.NODE to 1.0412,
        )
    }
}
