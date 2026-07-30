package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.perf.Complexity.LINEAR
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.COMPUTE
import io.github.mbackschat.a12.dm.adapter.perf.PerfOp.VALIDATE
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.COMPUTE_CLEARED
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.COMPUTE_ERRORED
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.COMPUTE_VALUE
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.VALIDATION_LOW_FIRING
import io.github.mbackschat.a12.dm.adapter.perf.ResultRegime.VALIDATION_MIXED
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ScenarioIdentityFingerprintTest {

    private fun draft(
        op: PerfOp = VALIDATE,
        regime: ResultRegime = VALIDATION_MIXED,
    ) = ScenarioId.builder("test-case")
        .operation(op)
        .resultRegime(regime)
        .complexity(LINEAR)
        .generator(GeneratorIdentity("fixed-test-generator", seed = 17, parameters = mapOf("shape" to "flat")))
        .scaleAxis("rows")
        .expectedResultCardinality(listOf(2))
        .build()

    @Test
    fun reviewedFingerprintRejectsModelValueRegimeAndScaleDriftWithoutAVersionUpdate() {
        val scenario = PerfCatalog.b1()
        val draft = draft()
        val reviewed = draft.review(scenario, n = 4)
        assertDoesNotThrow { reviewed.bind(scenario, n = 4) }

        val changedModel = PerfScenario(
            scenario.name,
            scenario.kernelModel,
            scenario.interpModel.copy(
                rules = scenario.interpModel.rules + RuleDef("/Perf/Extra", "/Perf", "1 == 2", "EXTRA", "../Lines/Qty"),
            ),
            scenario.op,
            scenario.locale,
            scenario.instance,
        )
        assertThrows<IllegalStateException> { reviewed.bind(changedModel, n = 4) }

        val changedFieldMessage = PerfScenario(
            scenario.name,
            scenario.kernelModel,
            scenario.interpModel.copy(
                fields = scenario.interpModel.fields.mapIndexed { index, field ->
                    if (index == 0) field.copy(patternMessages = mapOf("en_US" to "changed")) else field
                },
            ),
            scenario.op,
            scenario.locale,
            scenario.instance,
        )
        assertThrows<IllegalStateException> { reviewed.bind(changedFieldMessage, n = 4) }

        val changedValue = PerfScenario(
            scenario.name,
            scenario.kernelModel,
            scenario.interpModel,
            scenario.op,
        ) { sink, n, variant ->
            scenario.instance(sink, n, variant)
            sink.field(PerfCatalog.QTY, "999", 1, 1, 1)
        }
        assertThrows<IllegalStateException> { reviewed.bind(changedValue, n = 4) }
        assertThrows<IllegalStateException> {
            reviewed.withResultRegime(VALIDATION_LOW_FIRING).bind(scenario, n = 4)
        }
        assertThrows<IllegalStateException> { reviewed.bind(scenario, n = 5) }
    }

    @Test
    fun computationOutcomeRegimesHaveDistinctContentIdentities() {
        val scenario = PerfCatalog.b7()
        val fingerprints = listOf(COMPUTE_VALUE, COMPUTE_CLEARED, COMPUTE_ERRORED).map { regime ->
            draft(op = COMPUTE, regime = regime).fingerprintFor(scenario, n = 2)
        }

        assertTrue(fingerprints.toSet().size == 3, "VALUE/CLEARED/ERRORED must not collapse to one computation identity")
    }

    @Test
    fun legalCharsetContentAndExecutionLocaleAreIdentityBound() {
        val scenario = PerfCatalog.b1()
        fun withCharset(charset: LegalCharset) = PerfScenario(
            scenario.name,
            scenario.kernelModel,
            scenario.interpModel.copy(legalCharset = charset),
            scenario.op,
            scenario.locale,
            scenario.instance,
        )
        val az = withCharset(LegalCharset.ofRanges('A'.code..'Z'.code))
        val ay = withCharset(LegalCharset.ofRanges('A'.code..'Y'.code))
        val reviewed = draft().review(az, n = 4)

        assertNotEquals(reviewed.fingerprintFor(az, n = 4), reviewed.fingerprintFor(ay, n = 4))
        assertThrows<IllegalStateException> { reviewed.bind(ay, n = 4) }

        val german = PerfScenario(
            scenario.name,
            scenario.kernelModel,
            scenario.interpModel,
            scenario.op,
            locale = "de_DE",
            instance = scenario.instance,
        )
        assertThrows<IllegalStateException> { draft().review(scenario, n = 4).bind(german, n = 4) }
    }

    @Test
    fun formattedRowsCarryTheCompleteIdentity() {
        val scenario = PerfCatalog.b1()
        val draft = draft()
        val reviewed = draft.review(scenario, n = 4)
        val identity = reviewed.bind(scenario, n = 4).identity.complete(observedResultCardinality = listOf(2))
        val stats = Stats(medianMs = 1.0, minMs = 0.9, p90Ms = 1.1)

        val single = PerfHarness.format(PerfRow(identity, n = 4, firings = 2, stats, null, null))
        val repeated = PerfHarness.formatRepeated(RepeatedRow(identity, n = 4, variants = 1, stats, null, null))
        val direct = PerfHarness.measurementLabel(reviewed.bind(scenario, n = 4), n = 4)

        for (line in listOf(single, repeated, direct)) {
            assertTrue("test-case@1" in line, line)
            assertTrue(identity.fingerprint in line, line)
        }
        assertNotEquals(identity.id.version.toString(), identity.fingerprint)
    }

    @Test
    fun runtimeBindingsArePartOfTheFingerprint() {
        val scenario = PerfCatalog.b1()
        fun at(instant: String) = scenario.withRuntimeBindings(
            PerfRuntimeBindings.builder()
                .clock("2026-07-21", "2026-07-21T12:00:00", Instant.parse(instant))
                .build(),
        )

        val morning = draft().fingerprintFor(at("2026-07-21T10:00:00Z"), n = 4)
        val evening = draft().fingerprintFor(at("2026-07-21T20:00:00Z"), n = 4)

        assertNotEquals(morning, evening)
    }
}
