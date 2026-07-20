package io.github.mbackschat.a12.dm.interpreter.footprint

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FootprintScenariosTest {

    @Test
    fun scenarioIdentityMaterialIsTheCanonicalModelArtifactPlusGeneratorMetadata() {
        FootprintScenario.entries.forEach { scenario ->
            val fixture = FootprintScenarios.fixture(scenario, SCALE)

            assertEquals(
                "${FootprintModelFixtures.jsonFor(scenario)}\nworkload=${scenario.generatorIdentity}",
                fixture.contentDefinition,
            )
        }
    }

    @Test
    fun everyScenarioHasExactSupportedSemanticsAndExercisesItsDeclaredCaches() {
        FootprintScenario.entries.forEach { scenario ->
            val fixture = FootprintScenarios.fixture(scenario, SCALE)
            val observer = FootprintCacheObserver()
            val interpreter = DmInterpreter(fixture.model).also { it.cacheObserver = observer }

            val result = fixture.evaluate(interpreter)
            val caches = observer.snapshot()

            assertTrue(result.supported, "${scenario.id} must not enter an unsupported path")
            assertEquals(scenario.expectedOutcome, result.outcome)
            assertEquals(scenario.expectedCardinality(SCALE), result.cardinality)
            assertTrue(caches.document > 0, "${scenario.id} must exercise document caches")
            assertTrue(caches.parsedModel > 0, "${scenario.id} must exercise parsed-model caches")
            assertEquals(0, caches.overlayAfterRelease, "${scenario.id} must release overlay caches after a step")
            if (scenario == FootprintScenario.COMPUTE_OVERLAY) {
                assertTrue(caches.overlayPeak > 0, "the compute scenario must exercise overlay-view caches")
            }
        }
    }

    @Test
    fun computePreflightCoversEveryCascadeTargetValueAndRowIdentity() {
        val fixture = FootprintScenarios.fixture(FootprintScenario.COMPUTE_OVERLAY, SCALE)
        val key = fixture.model.fields.single { it.path.endsWith("/Key") }
        val source = fixture.model.fields.single { it.path.endsWith("/Source") }
        val changed = fixture.model.newDocument()
        repeat(SCALE) { row ->
            changed.field(key.path, if (row == 1) "kb" else "k$row", 1, row + 1, 1)
                .field(source.path, if (row == 0) "11" else "10", 1, row + 1, 1)
        }

        val result = fixture.evaluate(DmInterpreter(fixture.model), changed)

        assertNotEquals(
            FootprintScenario.COMPUTE_OVERLAY.expectedOutcome,
            result.outcome,
            "changing a non-index-source row must be visible through the intermediate/correlated signature",
        )
    }

    private companion object {
        const val SCALE = 4
    }
}
