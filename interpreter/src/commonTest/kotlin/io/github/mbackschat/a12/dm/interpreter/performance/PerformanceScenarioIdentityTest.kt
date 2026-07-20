package io.github.mbackschat.a12.dm.interpreter.performance

import kotlin.test.Test
import kotlin.test.assertNotEquals
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

class PerformanceScenarioIdentityTest {

    @Test
    fun identityBindsGeneratedDocumentConfigurationScaleAndExpectedSemantics() {
        val fixture = PerformanceScenarios.fixture(PerformanceScenario.NODE_B1_THROUGHPUT, 2_500)
        val baseline = fixture.identityContent()

        assertNotEquals(baseline, fixture.copy(locale = "de_DE").identityContent())
        assertNotEquals(baseline, fixture.copy(timeZone = ModelTimeZone.UTC).identityContent())
        assertNotEquals(baseline, fixture.copy(scale = fixture.scale + 1).identityContent())
        assertNotEquals(
            baseline,
            fixture.copy(
                expected = fixture.expected.copy(
                    cardinality = fixture.expected.cardinality + 1,
                    outcome = "codes=CHANGED",
                ),
            ).identityContent(),
        )
        fixture.document.field("/Perf/Lines/Qty", "identity-mutation", 1, 1, 1)
        assertNotEquals(baseline, fixture.identityContent())
    }

    @Test
    fun multiScaleIdentityBindsEveryActualFixture() {
        val scenario = PerformanceScenario.NODE_B1_THROUGHPUT
        val fixtures = scenario.scales.map { PerformanceScenarios.fixture(scenario, it) }
        val baseline = PerformanceScenarios.identityContent(fixtures)

        fixtures.last().document.field("/Perf/Lines/Qty", "identity-mutation", 1, 1, 1)

        assertNotEquals(baseline, PerformanceScenarios.identityContent(fixtures))
    }
}
