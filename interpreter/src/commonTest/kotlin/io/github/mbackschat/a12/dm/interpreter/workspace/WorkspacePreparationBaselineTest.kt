package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Independently consumable preparation baseline: the same typed-authored raw source is embedded into commonTest and
 * loaded/evaluated by the one common interpreter on both JVM and Node. This self-contained case proves the fixture
 * bridge itself does not depend on a JVM filesystem or the Kernel.
 */
class WorkspacePreparationBaselineTest {

    @Test
    fun selfContainedRawModelLoadsAndEvaluatesOnEveryTarget() {
        val caseId = "self-contained"
        val sources = WorkspacePreparationFixtureJson.sources(caseId)
        val model = ModelLoader.load(sources.getValue(WorkspacePreparationFixtureJson.entryModelId(caseId)))

        assertEquals(
            setOf(
                "/Permit/ApplicationNo",
                "/Permit/RequestedArea",
                "/Permit/ReviewedArea",
                "/Permit/Note",
                "/Permit/Rate",
                "/Permit/PatternProbe",
                "/Permit/GraphemeProbe",
                "/Permit/ReportedAt",
                "/Permit/SettledAt",
            ),
            model.fields.map { it.path }.toSet(),
        )
        assertEquals(
            setOf(
                "/Permit/AreaNeedsNote",
                "/Permit/DecimalPrecisionControl",
                "/Permit/DecimalPrecisionSplit",
                "/Permit/PatternDialect",
                "/Permit/GraphemeDialect",
                "/Permit/BerlinSpringHour",
            ),
            model.rules.map { it.path }.toSet(),
        )
        assertEquals(
            mapOf("/Permit/CopyRequestedArea" to "/Permit/ReviewedArea"),
            model.computations.associate {
                "${it.group}/${requireNotNull(it.name)}" to it.targetPath
            },
        )

        val document = model.newDocument()
            .field("/Permit/ApplicationNo", "P-100", 1, 1)
            .field("/Permit/RequestedArea", "150", 1, 1)
        val interpreter = DmInterpreter(model)

        assertEquals(listOf("AREA_NEEDS_NOTE"), interpreter.validateFull(document).map { it.code })
        val computed = interpreter.compute(document).single()
        assertEquals("/Permit/ReviewedArea", computed.path)
        assertEquals(ComputeOutcome.VALUE, computed.outcome)
        assertEquals("150", computed.value)

        val hostBoundary = model.newDocument()
            .field("/Permit/Rate", "0.30", 1, 1)
            .field("/Permit/PatternProbe", "12 34", 1, 1)
            .field("/Permit/GraphemeProbe", "e\u0301", 1, 1)
            .field("/Permit/ReportedAt", "2024-03-31T01:30:00", 1, 1)
            .field("/Permit/SettledAt", "2024-03-31T03:30:00", 1, 1)
        assertEquals(
            listOf(
                "BERLIN_SPRING_HOUR",
                "DECIMAL_CONTROL",
                "GRAPHEME_DIALECT",
                "PATTERN_DIALECT",
            ),
            interpreter.validateFull(hostBoundary).map { it.code }.sorted(),
        )
    }
}
