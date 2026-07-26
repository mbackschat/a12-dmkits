package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspacePreparationFixtureJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PublicInterpreterApiTest {

    @Test
    fun preparedModelExposesOwnerCheckedPointersValidationAndComputation() {
        val prepared = preparedPermit()
        val metadata = prepared.model
        val requestedArea = assertIs<FieldPointer>(
            metadata.pointer("/Permit/RequestedArea", listOf(1, 1)),
        )
        val permit = assertIs<GroupPointer>(
            metadata.pointer("/Permit", listOf(1)),
        )
        val snapshot = prepared.documents.readPlacements(
            PlacementDocumentInputV1(
                groups = listOf(GroupPlacementInput(permit.path, permit.coordinates)),
                fields = listOf(
                    FieldPlacementInput("/Permit/ApplicationNo", listOf(1, 1), "P-42"),
                    FieldPlacementInput(requestedArea.path, requestedArea.coordinates, "120"),
                ),
            ),
        )
        val interpreter = prepared.createInterpreter()

        val support = interpreter.supportReport()
        val validation = interpreter.validateFull(snapshot)
        val computation = interpreter.compute(snapshot)

        assertTrue(support.fullySupported)
        assertEquals("permit-basic", metadata.documentModelId)
        assertEquals(listOf("AREA_NEEDS_NOTE"), validation.findings.map(ValidationFinding::code))
        assertFalse(validation.noErrorOccurred)
        assertTrue(validation.fullySupported)
        assertEquals(1, computation.results.size)
        assertEquals("/Permit/ReviewedArea", computation.results.single().pointer.path)
        assertEquals(ComputationOutcome.VALUE, computation.results.single().outcome)
        assertEquals("120", computation.results.single().value)
        assertTrue(computation.fullySupported)
    }

    @Test
    fun partialValidationUsesModelOwnedRelevantPointers() {
        val prepared = preparedPermit()
        val snapshot = prepared.documents.readPlacements(
            PlacementDocumentInputV1(
                groups = listOf(GroupPlacementInput("/Permit", listOf(1))),
                fields = listOf(
                    FieldPlacementInput("/Permit/RequestedArea", listOf(1, 1), "120"),
                ),
            ),
        )
        val relevant = prepared.model.relevantPointer(
            "/Permit/Note",
            listOf(RelevantCoordinate.exact(1), RelevantCoordinate.exact(1)),
        )

        val report = prepared.createInterpreter().validatePart(snapshot, listOf(relevant))

        assertTrue(report.findings.isEmpty())
        assertTrue(report.fullySupported)
    }

    @Test
    fun pointerFactoriesAndRuntimeCallsRejectInvalidOrForeignObjects() {
        val first = preparedPermit()
        val second = preparedPermit()
        val firstSnapshot = first.documents.readPlacements(
            PlacementDocumentInputV1(emptyList(), emptyList()),
        )

        assertEquals(
            DocumentCoordinateReason.COORDINATE_COUNT_MISMATCH,
            assertFailsWith<DocumentCoordinateError> {
                first.model.pointer("/Permit/Note", listOf(1))
            }.reason,
        )
        assertFailsWith<UnknownModelPathError> {
            first.model.pointer("/Permit/Missing", listOf(1, 1))
        }
        assertFailsWith<ModelOwnerMismatchError> {
            second.createInterpreter().validateFull(firstSnapshot)
        }
    }

    @Test
    fun explicitCustomizationRegistriesRejectEmptyAndDuplicateNames() {
        assertEquals(
            CustomizationRegistrationReason.EMPTY_NAME,
            assertFailsWith<CustomizationRegistrationError> {
                CustomConditionRegistry.builder()
                    .register("", CustomCondition { false })
            }.reason,
        )
        assertEquals(
            CustomizationRegistrationReason.DUPLICATE_NAME,
            assertFailsWith<CustomizationRegistrationError> {
                CustomFieldTypeRegistry.builder()
                    .register("Code", CustomFieldValidator { _, _ -> CustomFieldValidationResult.Accepted })
                    .register("Code", CustomFieldValidator { _, _ -> CustomFieldValidationResult.Accepted })
            }.reason,
        )
    }

    private fun preparedPermit(): PreparedModel {
        val caseId = "self-contained"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val source = WorkspaceModelSource.builder()
            .json(WorkspacePreparationFixtureJson.sources(caseId).getValue(entryModelId))
            .build()
        return ModelWorkspace.fromSources(listOf(source)).prepare(entryModelId)
    }
}
