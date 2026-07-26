package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Public placement-transport contract (R2.8): the versioned envelope decodes through one shared validation
 * engine into a model-owned frozen [DocumentSnapshot], encodes back losslessly, aggregates every model-level
 * defect before construction, fails resource limits fast, and refuses foreign or stale snapshots by owner
 * identity. Coordinates above a declared maximum — a non-repeatable group's coordinate 2 included — stay
 * representable for formal validation (measured Kernel basis: `OverRepetitionFormalGateDiffTest`).
 */
class PlacementTransportTest {

    @Test
    fun roundTripPreservesStatesRowsAndRawTextAndReachesItsFixedPoint() {
        val documents = prepared().documents
        val input = PlacementDocumentInputV1(
            groups = listOf(
                GroupPlacementInput("/Order", listOf(1)),
                GroupPlacementInput("/Order/Items", listOf(1, 2)),
                GroupPlacementInput("/Order/Attachments", listOf(1, 1)),
            ),
            fields = listOf(
                FieldPlacementInput("/Order/Ref", listOf(1, 1), "ORD-7"),
                FieldPlacementInput("/Order/Items/Qty", listOf(1, 1, 1), "2"),
                FieldPlacementInput("/Order/Items/Note", listOf(1, 2, 1), ""),
                FieldPlacementInput("/Order/Items/Qty", listOf(1, 3, 1), "not-a-number"),
                FieldPlacementInput("/Order/Shipping/Mode", listOf(1, 1, 1), "express"),
            ),
        )

        val snapshot = documents.readPlacements(input)
        val encoded = documents.encodePlacements(snapshot)

        assertEquals(
            listOf(
                GroupPlacementInput("/Order", listOf(1)),
                GroupPlacementInput("/Order/Items", listOf(1, 2)),
                GroupPlacementInput("/Order/Attachments", listOf(1, 1)),
                GroupPlacementInput("/Order/Items", listOf(1, 1)),
                GroupPlacementInput("/Order/Items", listOf(1, 3)),
                GroupPlacementInput("/Order/Shipping", listOf(1, 1)),
            ),
            encoded.groups,
            "explicit rows keep their authored order; auto-instantiated ancestors follow in placement order",
        )
        assertEquals(
            listOf(
                FieldPlacementInput("/Order/Ref", listOf(1, 1), "ORD-7"),
                FieldPlacementInput("/Order/Items/Qty", listOf(1, 1, 1), "2"),
                FieldPlacementInput("/Order/Items/Note", listOf(1, 2, 1), ""),
                FieldPlacementInput("/Order/Items/Qty", listOf(1, 3, 1), "not-a-number"),
                FieldPlacementInput("/Order/Shipping/Mode", listOf(1, 1, 1), "express"),
            ),
            encoded.fields,
            "present-empty and malformed raw text survive verbatim; absent cells stay absent",
        )
        assertEquals(1, encoded.version)

        val reEncoded = documents.encodePlacements(documents.readPlacements(encoded))
        assertEquals(encoded, reEncoded, "encode of decode(encode(...)) is the fixed point")
    }

    @Test
    fun readAggregatesEveryModelLevelDefectBeforeConstruction() {
        val documents = prepared().documents
        val input = PlacementDocumentInputV1(
            groups = listOf(
                GroupPlacementInput("/Order/Nowhere", listOf(1, 1)),
                GroupPlacementInput("/Order/Items", listOf(1)),
                GroupPlacementInput("/Order", listOf(1)),
                GroupPlacementInput("/Order", listOf(1)),
            ),
            fields = listOf(
                FieldPlacementInput("/Order/Missing", listOf(1, 1), "x"),
                FieldPlacementInput("/Order/Ref", listOf(1, 0), "a"),
                FieldPlacementInput("/Order/Items/Qty", listOf(1, 1, 1), "2"),
                FieldPlacementInput("/Order/Items/Qty", listOf(1, 1, 1), "3"),
            ),
        )

        val error = assertFailsWith<DocumentInputError> {
            documents.readPlacements(input)
        }

        val byReason = error.diagnostics.groupBy { it.reasonCode }
        assertEquals(
            setOf(
                DocumentInputReasonCode.UNKNOWN_MODEL_PATH,
                DocumentInputReasonCode.COORDINATE_COUNT_MISMATCH,
                DocumentInputReasonCode.INVALID_COORDINATE,
                DocumentInputReasonCode.DUPLICATE_PLACEMENT,
                DocumentInputReasonCode.CONFLICTING_PLACEMENT,
            ),
            byReason.keys,
        )
        assertEquals(2, byReason.getValue(DocumentInputReasonCode.UNKNOWN_MODEL_PATH).size)

        val unknownGroup = byReason.getValue(DocumentInputReasonCode.UNKNOWN_MODEL_PATH)
            .single { it.placementKind == DocumentPlacementKind.GROUP }
        assertEquals(0, unknownGroup.placementIndex)
        assertEquals("/Order/Nowhere", unknownGroup.modelPath)

        val arity = byReason.getValue(DocumentInputReasonCode.COORDINATE_COUNT_MISMATCH).single()
        assertEquals(DocumentPlacementKind.GROUP, arity.placementKind)
        assertEquals(1, arity.placementIndex)
        assertEquals(2, arity.expectedCoordinateCount)
        assertEquals(1, arity.actualCoordinateCount)

        val coordinate = byReason.getValue(DocumentInputReasonCode.INVALID_COORDINATE).single()
        assertEquals(DocumentPlacementKind.FIELD, coordinate.placementKind)
        assertEquals(1, coordinate.placementIndex)
        assertEquals(1, coordinate.coordinateIndex)

        val duplicate = byReason.getValue(DocumentInputReasonCode.DUPLICATE_PLACEMENT).single()
        assertEquals(DocumentPlacementKind.GROUP, duplicate.placementKind)
        assertEquals(3, duplicate.placementIndex)
        assertEquals(2, duplicate.firstPlacementIndex)

        val conflict = byReason.getValue(DocumentInputReasonCode.CONFLICTING_PLACEMENT).single()
        assertEquals(DocumentPlacementKind.FIELD, conflict.placementKind)
        assertEquals(3, conflict.placementIndex)
        assertEquals(2, conflict.firstPlacementIndex)
    }

    @Test
    fun readRejectsAnUnsupportedVersionAsTheSoleDiagnostic() {
        val documents = prepared().documents
        val error = assertFailsWith<DocumentInputError> {
            documents.readPlacements(
                PlacementDocumentInputV1(
                    groups = listOf(GroupPlacementInput("/Order/Nowhere", listOf(1))),
                    fields = emptyList(),
                    version = 2,
                ),
            )
        }

        val diagnostic = error.diagnostics.single()
        assertEquals(DocumentInputReasonCode.UNSUPPORTED_VERSION, diagnostic.reasonCode)
        assertEquals(1, diagnostic.expectedVersion)
        assertEquals(2, diagnostic.actualVersion)
    }

    @Test
    fun eachDecodeLimitFailsFastWithItsTypedResource() {
        val documents = prepared().documents
        val cases = listOf(
            Triple(
                DocumentDecodeLimits.builder().maxGroupPlacements(1).build(),
                PlacementDocumentInputV1(
                    groups = listOf(
                        GroupPlacementInput("/Order", listOf(1)),
                        GroupPlacementInput("/Order/Items", listOf(1, 1)),
                    ),
                    fields = emptyList(),
                ),
                DocumentInputResource.GROUP_PLACEMENTS,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxFieldPlacements(1).build(),
                PlacementDocumentInputV1(
                    groups = emptyList(),
                    fields = listOf(
                        FieldPlacementInput("/Order/Ref", listOf(1, 1), "a"),
                        FieldPlacementInput("/Order/Shipping/Mode", listOf(1, 1), "b"),
                    ),
                ),
                DocumentInputResource.FIELD_PLACEMENTS,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxCoordinatesPerPlacement(2).build(),
                PlacementDocumentInputV1(
                    groups = emptyList(),
                    fields = listOf(FieldPlacementInput("/Order/Items/Qty", listOf(1, 1, 1), "2")),
                ),
                DocumentInputResource.COORDINATES_PER_PLACEMENT,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxPathCodeUnits(8).build(),
                PlacementDocumentInputV1(
                    groups = emptyList(),
                    fields = listOf(FieldPlacementInput("/Order/Ref", listOf(1, 1), "a")),
                ),
                DocumentInputResource.PATH_CODE_UNITS,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxRawCodeUnitsPerField(3).build(),
                PlacementDocumentInputV1(
                    groups = emptyList(),
                    fields = listOf(FieldPlacementInput("/Order/Ref", listOf(1, 1), "abcd")),
                ),
                DocumentInputResource.RAW_CODE_UNITS_PER_FIELD,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxTotalRawCodeUnits(5).build(),
                PlacementDocumentInputV1(
                    groups = emptyList(),
                    fields = listOf(
                        FieldPlacementInput("/Order/Ref", listOf(1, 1), "abc"),
                        FieldPlacementInput("/Order/Shipping/Mode", listOf(1, 1), "def"),
                    ),
                ),
                DocumentInputResource.TOTAL_RAW_CODE_UNITS,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxModelNestingDepth(1).build(),
                PlacementDocumentInputV1(
                    groups = emptyList(),
                    fields = listOf(FieldPlacementInput("/Order/Ref", listOf(1, 1), "a")),
                ),
                DocumentInputResource.MODEL_NESTING_DEPTH,
            ),
        )

        for ((limits, input, resource) in cases) {
            val error = assertFailsWith<DocumentInputError>(resource.name) {
                prepared().documents.readPlacements(input, limits)
            }
            val diagnostic = error.diagnostics.single()
            assertEquals(DocumentInputReasonCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.reasonCode, resource.name)
            assertEquals(resource, diagnostic.resource, resource.name)
            assertNotNull(diagnostic.maximum, resource.name)
            assertNotNull(diagnostic.actual, resource.name)
        }
        // The touched instance stays untouched by the failing sibling cases above.
        documents.readPlacements(PlacementDocumentInputV1(emptyList(), emptyList()))
    }

    @Test
    fun callerLimitsAreCappedByTheHardMaxima() {
        val widened = DocumentDecodeLimits.builder()
            .maxFieldPlacements(DocumentDecodeLimits.HARD_MAX_FIELD_PLACEMENTS + 1)
            .build()

        assertEquals(DocumentDecodeLimits.HARD_MAX_FIELD_PLACEMENTS, widened.maxFieldPlacements)
        assertFails { DocumentDecodeLimits.builder().maxGroupPlacements(0) }
    }

    @Test
    fun coordinatesAboveDeclaredMaximaStayRepresentableIncludingNonRepeatableGroups() {
        val documents = prepared().documents
        val input = PlacementDocumentInputV1(
            groups = listOf(
                GroupPlacementInput("/Order/Items", listOf(1, 5)),
                GroupPlacementInput("/Order/Shipping", listOf(1, 2)),
            ),
            fields = listOf(
                FieldPlacementInput("/Order/Shipping/Mode", listOf(1, 2, 1), "courier"),
                FieldPlacementInput("/Order/Items/Qty", listOf(1, 9, 1), "4"),
            ),
        )

        val encoded = documents.encodePlacements(documents.readPlacements(input))

        assertTrue(GroupPlacementInput("/Order/Shipping", listOf(1, 2)) in encoded.groups)
        assertTrue(FieldPlacementInput("/Order/Items/Qty", listOf(1, 9, 1), "4") in encoded.fields)
    }

    @Test
    fun encodeRefusesForeignAndStaleSnapshotsByOwnerIdentity() {
        val first = prepared()
        val second = prepared()
        val snapshot = first.documents.readPlacements(
            PlacementDocumentInputV1(
                groups = listOf(GroupPlacementInput("/Order", listOf(1))),
                fields = emptyList(),
            ),
        )

        assertSame(first.owner, snapshot.owner)
        assertSame(first.modelVersion, snapshot.modelVersion)
        first.documents.encodePlacements(snapshot)

        val stale = assertFailsWith<ModelOwnerMismatchError> {
            second.documents.encodePlacements(snapshot)
        }
        assertSame(second.owner, stale.expected)
        assertSame(first.owner, stale.actual)

        val foreign = PreparedModel.loadExpandedJson(foreignModel())
        assertFailsWith<ModelOwnerMismatchError> {
            foreign.documents.encodePlacements(snapshot)
        }
    }

    @Test
    fun snapshotsAreImmuneToLaterInputMutationAndEncodeOutputIsRuntimeImmutable() {
        val documents = prepared().documents
        val coordinates = mutableListOf(1, 1)
        val fields = mutableListOf(FieldPlacementInput("/Order/Ref", coordinates, "ORD-9"))
        val input = PlacementDocumentInputV1(groups = mutableListOf(), fields = fields)

        val snapshot = documents.readPlacements(input)
        coordinates[1] = 7
        fields.clear()

        val encoded = documents.encodePlacements(snapshot)
        assertEquals(
            listOf(FieldPlacementInput("/Order/Ref", listOf(1, 1), "ORD-9")),
            encoded.fields,
        )
        assertFails { (encoded.fields as MutableList<FieldPlacementInput>).clear() }
        assertFails { (encoded.groups as MutableList<GroupPlacementInput>).clear() }
    }

    private fun prepared(): PreparedModel = PreparedModel.loadExpandedJson(ORDER_MODEL)

    private fun foreignModel(): String = """
        {
          "header":{"id":"claims"},
          "content":{
            "modelRoot":{"rootGroups":[{
              "type":"Group","id":"/Claim","name":"Claim",
              "Group":{"elements":[{
                "type":"Field","id":"/Claim/Number","name":"Number",
                "Field":{"fieldType":{"type":"StringType"}}
              }]}
            }]}
          }
        }
    """.trimIndent()

    private companion object {
        val ORDER_MODEL = """
            {
              "header":{"id":"orders"},
              "content":{
                "modelRoot":{"rootGroups":[{
                  "type":"Group","id":"/Order","name":"Order",
                  "Group":{"elements":[
                    {
                      "type":"Field","id":"/Order/Ref","name":"Ref",
                      "Field":{"fieldType":{"type":"StringType"}}
                    },
                    {
                      "type":"Group","id":"/Order/Items","name":"Items",
                      "Group":{"repeatability":3,"elements":[
                        {
                          "type":"Field","id":"/Order/Items/Qty","name":"Qty",
                          "Field":{"fieldType":{"type":"NumberType"}}
                        },
                        {
                          "type":"Field","id":"/Order/Items/Note","name":"Note",
                          "Field":{"fieldType":{"type":"StringType"}}
                        }
                      ]}
                    },
                    {
                      "type":"Group","id":"/Order/Shipping","name":"Shipping",
                      "Group":{"elements":[{
                        "type":"Field","id":"/Order/Shipping/Mode","name":"Mode",
                        "Field":{"fieldType":{"type":"StringType"}}
                      }]}
                    },
                    {
                      "type":"Group","id":"/Order/Attachments","name":"Attachments",
                      "Group":{}
                    }
                  ]}
                }]}
              }
            }
        """.trimIndent()
    }
}
