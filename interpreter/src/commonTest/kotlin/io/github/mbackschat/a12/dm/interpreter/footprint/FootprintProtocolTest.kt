package io.github.mbackschat.a12.dm.interpreter.footprint

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FootprintProtocolTest {

    @Test
    fun fiveIndependentForksPerHostWithCompleteScenariosAndScalesAreAccepted() {
        val forks = FootprintHost.entries.flatMap { host ->
            (1..FORKS).map { fork -> FootprintProtocol.parse(validFork(host, host.ordinal * 100 + fork)) }
        }

        val run = FootprintProtocol.validateForkSet(
            forks = forks,
            requiredForksPerHost = FORKS,
            expectedScales = SCALES,
            expectedScenarios = SCENARIOS,
        )

        assertEquals(FORKS * FootprintHost.entries.size, run.forks.size)
        assertEquals(SCALES, run.scales)
        assertEquals(SCENARIOS, run.scenarios)
    }

    @Test
    fun missingExplicitGcEvidenceIsRejected() {
        val malformed = validFork(FootprintHost.NODE, 1, includeExplicitGc = false)

        val failure = assertFailsWith<IllegalArgumentException> { FootprintProtocol.parse(malformed) }

        assertTrue(failure.message.orEmpty().contains("gc.explicit"))
    }

    @Test
    fun runtimeAndMeasurementMetadataCannotBeOmitted() {
        val valid = validFork(FootprintHost.JVM, 1)
        listOf(
            "\"invocationId\":\"JVM-1\",",
            "\"os\":\"test-os\",",
            "\"arch\":\"test-arch\",",
            "\"allocationMethod\":\"thread-allocated-bytes\",",
            "\"warmup\":2,",
        ).forEach { requiredField ->
            assertFailsWith<IllegalArgumentException> {
                FootprintProtocol.parse(valid.replace(requiredField, ""))
            }
        }
    }

    @Test
    fun nonPositiveRetainedOrAllocationSamplesAreRejectedInsteadOfClamped() {
        val retained = FootprintProtocol.parse(validFork(FootprintHost.JVM, 1, retainedBytes = 0))
        val allocated = FootprintProtocol.parse(validFork(FootprintHost.JVM, 2, allocatedBytes = -1))

        assertFailsWith<IllegalArgumentException> {
            FootprintProtocol.validateForkSet(listOf(retained), 1, SCALES, SCENARIOS)
        }
        assertFailsWith<IllegalArgumentException> {
            FootprintProtocol.validateForkSet(listOf(allocated), 1, SCALES, SCENARIOS)
        }
    }

    @Test
    fun reusedProcessAndIncompleteScaleOrCacheCountsAreRejected() {
        val reused = FootprintProtocol.parse(validFork(FootprintHost.JVM, 41))
        assertFailsWith<IllegalArgumentException> {
            FootprintProtocol.validateForkSet(listOf(reused, reused), 2, SCALES, SCENARIOS)
        }
        val reusedInvocation = listOf(
            FootprintProtocol.parse(validFork(FootprintHost.JVM, 44, invocationId = "same-invocation")),
            FootprintProtocol.parse(validFork(FootprintHost.JVM, 45, invocationId = "same-invocation")),
        )
        assertFailsWith<IllegalArgumentException> {
            FootprintProtocol.validateForkSet(reusedInvocation, 2, SCALES, SCENARIOS)
        }

        val incompleteScale = FootprintProtocol.parse(
            validFork(FootprintHost.JVM, 42, omittedScale = SCALES.last()),
        )
        assertFailsWith<IllegalArgumentException> {
            FootprintProtocol.validateForkSet(listOf(incompleteScale), 1, SCALES, SCENARIOS)
        }

        val incompleteCounts = validFork(FootprintHost.JVM, 43, includeOverlayAfterRelease = false)
        val failure = assertFailsWith<IllegalArgumentException> { FootprintProtocol.parse(incompleteCounts) }
        assertTrue(failure.message.orEmpty().contains("cacheEntries.overlayAfterRelease"))
    }

    @Test
    fun leakingOwnerOrUnsupportedAndWrongSemanticOutcomeIsRejected() {
        val leaking = FootprintProtocol.parse(validFork(FootprintHost.JVM, 1, ownersReleased = false))
        val unsupported = FootprintProtocol.parse(validFork(FootprintHost.JVM, 2, supported = false))
        val wrong = FootprintProtocol.parse(validFork(FootprintHost.JVM, 3, semanticOutcome = "WRONG"))

        listOf(leaking, unsupported, wrong).forEach { fork ->
            assertFailsWith<IllegalArgumentException> {
                FootprintProtocol.validateForkSet(listOf(fork), 1, SCALES, SCENARIOS)
            }
        }
    }

    private fun validFork(
        host: FootprintHost,
        pid: Int,
        invocationId: String = "${host.name}-$pid",
        includeExplicitGc: Boolean = true,
        retainedBytes: Long = 10_000,
        allocatedBytes: Long = 20_000,
        omittedScale: Int? = null,
        includeOverlayAfterRelease: Boolean = true,
        ownersReleased: Boolean = true,
        supported: Boolean = true,
        semanticOutcome: String? = null,
    ): String {
        val gc = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        if (includeExplicitGc) gc["explicit"] = JsonPrimitive(true)
        gc["viable"] = JsonPrimitive(true)
        gc["method"] = JsonPrimitive(if (host == FootprintHost.JVM) "system-gc-settled" else "global-gc-settled")

        val samples = SCENARIOS.flatMap { scenario ->
            SCALES.filterNot { it == omittedScale }.map { scale ->
                val cacheEntries = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                    "document" to JsonPrimitive(12),
                    "parsedModel" to JsonPrimitive(3),
                    "overlayPeak" to JsonPrimitive(if (scenario == FootprintScenario.COMPUTE_OVERLAY) 4 else 0),
                )
                if (includeOverlayAfterRelease) cacheEntries["overlayAfterRelease"] = JsonPrimitive(0)
                cacheEntries["afterOwnerRelease"] = JsonPrimitive(0)
                JsonObject(
                    mapOf(
                        "scenario" to JsonPrimitive(scenario.id),
                        "scenarioIdentity" to JsonPrimitive(
                            ('a'.code + scenario.ordinal).toChar().toString().repeat(64),
                        ),
                        "scale" to JsonPrimitive(scale),
                        "retainedBytes" to JsonPrimitive(retainedBytes),
                        "allocatedBytes" to JsonPrimitive(allocatedBytes),
                        "cacheEntries" to JsonObject(cacheEntries),
                        "ownersReleased" to JsonPrimitive(ownersReleased),
                        "supported" to JsonPrimitive(supported),
                        "semanticOutcome" to JsonPrimitive(semanticOutcome ?: scenario.expectedOutcome),
                        "resultCardinality" to JsonPrimitive(scenario.expectedCardinality(scale)),
                    ),
                )
            }
        }
        return JsonObject(
            mapOf(
                "protocolVersion" to JsonPrimitive(1),
                "commit" to JsonPrimitive("0123456789abcdef"),
                "host" to JsonPrimitive(host.name),
                "invocationId" to JsonPrimitive(invocationId),
                "runtime" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(if (host == FootprintHost.JVM) "OpenJDK" else "Node"),
                        "version" to JsonPrimitive("test-runtime"),
                        "os" to JsonPrimitive("test-os"),
                        "arch" to JsonPrimitive("test-arch"),
                        "pid" to JsonPrimitive(pid),
                        "maxHeapBytes" to JsonPrimitive(256_000_000),
                        "collector" to JsonPrimitive(if (host == FootprintHost.JVM) "G1" else "V8"),
                    ),
                ),
                "gc" to JsonObject(gc),
                "allocationMethod" to JsonPrimitive(
                    if (host == FootprintHost.JVM) "thread-allocated-bytes" else "process-memory-usage",
                ),
                "scales" to JsonArray(SCALES.map(::JsonPrimitive)),
                "warmup" to JsonPrimitive(2),
                "repetitions" to JsonPrimitive(3),
                "samples" to JsonArray(samples),
            ),
        ).toString()
    }

    private companion object {
        const val FORKS = 5
        val SCALES = listOf(100, 200, 400, 800)
        val SCENARIOS = FootprintScenario.entries
    }
}
