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

    /**
     * The does-fire proof carried by REAL measured regions rather than by round synthetic numbers.
     *
     * These three wall/cpu/gc triples are verbatim from `jvm-throughput-1` of the 2026-09-20 starvation arm —
     * sixteen busy loops on eight cores, two-times oversubscription — where the measuring thread got roughly a
     * third of its elapsed time. The correction does not rescue them and must not: this is a genuinely starved
     * capture, not an allocating one, and the difference is the whole point of the pair of tests here. Kept as
     * literals because a fixture that merely *resembles* a starved capture would drift away from the band the
     * floor was set against.
     */
    @Test
    fun theMeasuredStarvationArmIsRefused() {
        val scenario = PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT
        val wall = listOf(766.60, 887.25, 805.94)
        val fork = fork(
            scenario, 1, "starved", wall, wall, warmup = 2,
            cpu = listOf(278.32, 286.29, 290.54),
            gc = listOf(48.0, 41.0, 49.0),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            PerformanceProtocol.aggregate(listOf(fork), 1, listOf(scenario))
        }
        assertTrue(
            failure.message!!.contains("occupancy"),
            "the refusal must name occupancy; was: ${failure.message}",
        )
    }

    /**
     * The regression lock for the defect this protocol shipped: an ALLOCATING but healthy capture is accepted.
     *
     * Measured 2026-09-20 on an idle machine, one scenario, the heap the only variable: at 320 m the plain
     * `cpu / wall` read 0.6868 — under the floor of the day — while the same capture's GC-corrected ratio read
     * 0.9962. The aggregate therefore refused a capture taken on a machine with nothing else running and told
     * the reader to re-measure on an idle one. The fixture below is that shape in round numbers: the plain
     * ratio is asserted to sit under the live floor, so this test still means something if the floor moves,
     * and the fork is nonetheless accepted.
     */
    @Test
    fun anAllocationHeavyCaptureIsAcceptedAlthoughItsPlainRatioWouldNotBe() {
        val scenario = PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT
        val wall = listOf(100.0, 100.0, 100.0)
        val fork = fork(
            scenario, 1, "allocating", wall, wall,
            warmup = 2, cpu = listOf(69.0, 69.0, 69.0), gc = listOf(30.0, 30.0, 30.0),
        )

        assertTrue(
            0.69 < PerformanceProtocol.minOccupancy(PerformanceHost.JVM),
            "the fixture must sit UNDER the live floor on the plain ratio, or it proves nothing",
        )
        PerformanceProtocol.aggregate(listOf(fork), 1, listOf(scenario))
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
            gcMillis = null,
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

    /**
     * A GC series that does not line up with the regions cannot attribute anything, so it is refused.
     *
     * Same reasoning as the cpu/wall length check: occupancy is a per-region ratio and the GC series exists to
     * explain a low one, which needs region `i`'s collection beside region `i`'s times. A shorter series is what
     * a partly-written record produces, and it would silently attribute one region's GC to another.
     */
    @Test
    fun aGcSeriesThatDoesNotMatchTheRegionsIsRefused() {
        val scenario = PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT
        // Both scales supplied, so the scale check cannot fire first and steal the red — the GC length is the
        // only thing wrong with this fork.
        val fork = fork(
            scenario, 1, "gc", listOf(10.0, 10.0, 10.0), listOf(20.0, 20.0, 20.0),
            warmup = 2, gc = listOf(1.0, 1.0),
        )

        val failure = assertFailsWith<IllegalArgumentException> { PerformanceProtocol.validateFork(fork) }
        assertTrue(
            failure.message!!.contains("gc"),
            "the refusal must name the GC series; was: ${failure.message}",
        )
    }

    /** A negative collection time is not a smaller measurement, it is an unusable one. */
    @Test
    fun aNegativeGcTimeIsRefused() {
        val scenario = PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT
        val fork = fork(
            scenario, 1, "gc", listOf(10.0, 10.0, 10.0), listOf(20.0, 20.0, 20.0),
            warmup = 2, gc = listOf(1.0, -1.0, 1.0),
        )

        assertFailsWith<IllegalArgumentException> { PerformanceProtocol.validateFork(fork) }
    }

    /**
     * The carrier that cannot report GC round-trips as ABSENT, not as zeros.
     *
     * `0.0` would read as "this region saw no collection", which Node never measured — the absence-versus-zero
     * rule (IF285). Both directions are asserted here because the renderer omits the key entirely, and a parser
     * that defaulted it to an empty list would turn "cannot say" into "said nothing happened".
     */
    @Test
    fun anAbsentGcSeriesSurvivesTheRoundTripAsAbsent() {
        val present = fork(
            PerformanceScenario.JVM_B1_ABSOLUTE_THROUGHPUT, 7, "jvm", listOf(2.0, 1.0, 3.0),
            warmup = 2, gc = listOf(0.0, 4.0, 0.0),
        )
        val absent = fork(PerformanceScenario.NODE_B1_THROUGHPUT, 8, "node", listOf(2.0, 1.0, 3.0), warmup = 2)

        assertEquals(present, PerformanceProtocol.parse(PerformanceProtocol.render(present)))
        assertEquals(absent, PerformanceProtocol.parse(PerformanceProtocol.render(absent)))
        assertEquals(null, PerformanceProtocol.parse(PerformanceProtocol.render(absent)).samples.first().gcMillis)
    }

    /**
     * GC leaves the denominator, so allocation cannot masquerade as contention.
     *
     * This case is arithmetic and stands on its own: 50 ms of CPU in a 100 ms region of which 50 ms was
     * collection is a thread that used every cycle available to it. The field capture that motivated the change
     * ran while the machine carried external load, so its figures are not quoted here and the arms are re-run on
     * a quiet box before any go into the record.
     */
    @Test
    fun occupancyTakesGcOutOfTheDenominator() {
        val sample = PerformanceScaleSample(
            scale = 1,
            elapsedMillis = listOf(100.0),
            cpuMillis = listOf(50.0),
            gcMillis = listOf(50.0),
            cardinality = 1, supported = true, outcome = "codes=X",
        )

        assertEquals(
            1.0, sample.occupancy(),
            "50 ms of CPU across 100 ms of wall of which 50 ms was collection is a thread that owned every " +
                "cycle available to it",
        )
    }

    /**
     * A region whose elapsed time is entirely accounted for by collection yields no contention evidence.
     *
     * GC *elapsed* time is an upper bound on the pause charged to the measuring thread, so the adjusted
     * denominator can reach zero. The conservative reading is the honest one for a contention floor: there is
     * nothing here that says the machine was busy, so the region must not be refused as though there were.
     */
    @Test
    fun aRegionFullyAccountedForByCollectionIsNotReadAsContention() {
        val sample = PerformanceScaleSample(
            scale = 1,
            elapsedMillis = listOf(100.0),
            cpuMillis = listOf(10.0),
            gcMillis = listOf(100.0),
            cardinality = 1, supported = true, outcome = "codes=X",
        )

        assertEquals(1.0, sample.occupancy(), "a wholly collecting region is not a starved one")
    }

    private fun fork(
        scenario: PerformanceScenario,
        pid: Long,
        invocation: String,
        timings: List<Double>,
        largeTimings: List<Double>? = null,
        warmup: Int = 0,
        cpu: List<Double>? = null,
        gc: List<Double>? = null,
    ): PerformanceFork {
        val timingSets = if (largeTimings == null) {
            listOf(timings)
        } else {
            listOf(timings, largeTimings)
        }
        return PerformanceFork(
            protocolVersion = 3,
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
                PerformanceScaleSample(scale, values, cpu ?: values, gc, 1, true, "codes=X")
            },
        )
    }

    /** A fork with explicit per-scale wall and cpu series, for the occupancy bar's two directions. */
    private fun forkWithCpu(
        scenario: PerformanceScenario,
        wall: List<List<Double>>,
        cpu: List<List<Double>>,
    ): PerformanceFork = PerformanceFork(
        protocolVersion = 3,
        commit = "12345678",
        runner = "pinned-test-runner",
        invocationId = "occupancy",
        host = scenario.host,
        runtime = PerformanceRuntime("runtime", "1", "os", "arch", 1, 1),
        scenario = scenario,
        scenarioIdentity = "a".repeat(64),
        warmup = 3,
        samples = scenario.scales.indices.map { index ->
            PerformanceScaleSample(scenario.scales[index], wall[index], cpu[index], null, 1, true, "codes=X")
        },
    )

    private companion object {
        /**
         * The lowest occupancy a HEALTHY capture produced per carrier — 12 JVM and 15 Node scale-samples of one
         * idle-machine bundle invocation, 2026-09-20, on the GC-corrected quantity.
         *
         * The floors must sit below these and a healthy capture at exactly these values must be accepted; both
         * directions are asserted above. The JVM figure is deliberately the **cold first-answer** outlier
         * (0.8328, no collection at all, the thread blocked on class loading while its sibling forks read
         * 0.9944 and 0.9921) rather than the other eleven of that invocation's twelve, which span
         * 0.9887 – 1.0000: a floor that admits only those would refuse an honest cold measurement. They differ by more than either floor's margin, which is the
         * measured reason the floor is keyed by host — the JVM numerator is the measuring thread's CPU, Node's
         * is every thread's.
         */
        val IDLE_MINIMUM = mapOf(
            PerformanceHost.JVM to 0.8328,
            PerformanceHost.NODE to 1.0407,
        )
    }
}
