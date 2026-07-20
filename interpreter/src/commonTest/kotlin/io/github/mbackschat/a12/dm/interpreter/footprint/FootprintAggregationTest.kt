package io.github.mbackschat.a12.dm.interpreter.footprint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FootprintAggregationTest {

    @Test
    fun validatedForksProduceInformativeMedianCurvesWithoutPromotingThresholds() {
        val aggregate = FootprintAggregation.aggregate(
            forks = FootprintHost.entries.flatMap { host -> (1..5).map { fork(host, it) } },
            requiredForksPerHost = 5,
        )

        assertEquals(12, aggregate.curves.size)
        val retained = aggregate.curves.single {
            it.host == FootprintHost.JVM &&
                it.scenario == FootprintScenario.SIMPLE_VALIDATION &&
                it.metric == FootprintMetric.RETAINED_BYTES
        }
        assertEquals(listOf(43.0, 83.0, 163.0, 323.0), retained.medians)
        assertTrue(retained.spaceExponent > 0.0)
        assertTrue(retained.worstFourXStep > 0.0)

        val rendered = Json.parseToJsonElement(FootprintAggregation.render(aggregate)).jsonObject
        assertEquals("true", rendered.getValue("informativeOnly").jsonPrimitive.content)
        assertEquals(COMMIT, rendered.getValue("commit").jsonPrimitive.content)
    }

    private fun fork(host: FootprintHost, fork: Int): FootprintFork = FootprintFork(
        protocolVersion = 1,
        commit = COMMIT,
        host = host,
        invocationId = "${host.name}-$fork",
        runtime = FootprintRuntime(
            name = host.name,
            version = "test",
            os = "test",
            arch = "test",
            pid = host.ordinal * 100L + fork,
            maxHeapBytes = 512_000_000,
            collector = "test",
        ),
        gc = FootprintGc(explicit = true, viable = true, method = "test"),
        allocationMethod = "test",
        scales = SCALES,
        warmup = 2,
        repetitions = 3,
        samples = FootprintScenario.entries.flatMap { scenario ->
            SCALES.map { scale ->
                FootprintSample(
                    scenario = scenario,
                    scenarioIdentity = ('a'.code + scenario.ordinal).toChar().toString().repeat(64),
                    scale = scale,
                    retainedBytes = scale * 10L + fork,
                    allocatedBytes = scale * 20L + fork,
                    cacheEntries = FootprintCacheEntries(
                        document = 10,
                        parsedModel = 2,
                        overlayPeak = if (scenario == FootprintScenario.COMPUTE_OVERLAY) 1 else 0,
                        overlayAfterRelease = 0,
                        afterOwnerRelease = 0,
                    ),
                    ownersReleased = true,
                    supported = true,
                    semanticOutcome = scenario.expectedOutcome,
                    resultCardinality = scenario.expectedCardinality(scale),
                )
            }
        },
    )

    private companion object {
        const val COMMIT = "0123456789abcdef"
        val SCALES = listOf(4, 8, 16, 32)
    }
}
