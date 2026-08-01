package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspacePreparationFixtureJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The showcase model must actually exercise **per-row identity** — several computation results whose
 * `pointer.path` is the *same* string, told apart only by their coordinates (IRG2).
 *
 * The capability itself is locked by the conformance corpus (`compute/order-stock-per-row`); what this test
 * locks is the *demonstration*: the model the browser showcase and the packaged consumers run must contain a
 * repeated computed field, so the page cannot go back to showing only repeatability-1 targets while the
 * capability quietly works. It also pins the conversion the showcase renders beside the raw pointer, because a
 * per-row result is unreadable until path and coordinates are joined into one pointer.
 */
class ShowcaseRepeatedComputationTest {

    @Test
    fun theShowcaseModelComputesOneResultPerRepeatedRow() {
        val perPath = repeatedRowResults()

        assertTrue(
            perPath.isNotEmpty(),
            "the showcase model computes into no repeated target, so the page cannot demonstrate per-row " +
                "identity; every result has a unique path",
        )
        perPath.forEach { (path, results) ->
            val coordinates = results.map { it.pointer.coordinates }
            assertEquals(
                coordinates.size,
                coordinates.distinct().size,
                "results at $path repeat a coordinate tuple, so the rows are not separately identified: " +
                    coordinates,
            )
        }
    }

    @Test
    fun eachRepeatedResultRendersItsOwnCanonicalPointer() {
        val rendered = repeatedRowResults().values.flatten().map { result ->
            A12PointerFormat.format(result.pointer.path, result.pointer.coordinates)
        }

        assertEquals(
            listOf("Permit/SiteSections/SurveyCharge", "Permit/SiteSections[2]/SurveyCharge"),
            rendered.sorted(),
            "the repeated rows do not render one distinct canonical pointer each",
        )
    }

    @Test
    fun applyingTheReportWritesEveryRepeatedRowSeparately() {
        val prepared = preparedShowcaseModel()
        val snapshot = twoSectionDocument(prepared)

        val applied = prepared.createInterpreter().compute(snapshot).applyTo(snapshot)

        val encoded = prepared.documents.encodeDocument(applied)
        assertTrue("\"SurveyCharge\":1875" in encoded, "row 1 charge missing from $encoded")
        assertTrue("\"SurveyCharge\":1250" in encoded, "row 2 charge missing from $encoded")
    }

    /** The computed paths that carry more than one result, i.e. the ones a repeated row produces. */
    private fun repeatedRowResults(): Map<String, List<ComputationResult>> {
        val prepared = preparedShowcaseModel()
        return prepared.createInterpreter()
            .compute(twoSectionDocument(prepared))
            .results
            .groupBy { it.pointer.path }
            .filterValues { it.size > 1 }
    }

    private fun preparedShowcaseModel(): PreparedModel {
        val caseId = "self-contained"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        return ModelWorkspace
            .fromSources(
                listOf(
                    WorkspaceModelSource.builder()
                        .json(WorkspacePreparationFixtureJson.sources(caseId).getValue(entryModelId))
                        .build(),
                ),
            )
            .prepare(entryModelId)
    }

    private fun twoSectionDocument(prepared: PreparedModel): DocumentSnapshot =
        prepared.documents.readDocument(
            """
            {"Permit":{"ApplicationNo":"P-42","RequestedArea":125,"UnitFee":2.50,
             "SiteSections":[{"SectionName":"Retail floor","Area":75},
                             {"SectionName":"Storage area","Area":50}]}}
            """.trimIndent(),
        )
}
