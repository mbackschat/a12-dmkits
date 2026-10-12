package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.StoredCellState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Public Document transport contract: the boundary reads and writes **A12's canonical Document JSON** — the
 * nested object tree the Kernel's `IDocumentV2Serializer` produces and consumes (KERNEL-FINDINGS "The canonical
 * Document JSON and `DocumentPointer` string"; CLAUDE.md "NEVER invent a shape A12 already defines"). One shared
 * engine converts the whole text before any document state exists, discards notification-only nodes, fails
 * resource limits fast, locates every defect by its canonical A12 pointer, and refuses foreign or stale
 * snapshots by owner identity.
 */
class DocumentTransportTest {

    @Test
    fun readMapsTheNestedTreeOntoRowsAndCellsAndEncodeReturnsTheCanonicalShape() {
        val documents = prepared().documents
        val json = """
            {"Order":{"Ref":"ORD-7","Express":true,"Placed":"2026-03-01",
              "Items":[{"Qty":250.00},{"Note":""},{}],
              "Shipping":{"Mode":"express"},"Attachments":{}}}
        """.trimIndent()

        val snapshot = documents.readDocument(json)
        val document = snapshot.document()

        assertEquals(listOf(listOf(1)), document.instantiatedRows("/Order"))
        assertEquals(
            listOf(listOf(1, 1), listOf(1, 2), listOf(1, 3)),
            document.instantiatedRows("/Order/Items"),
            "an array index IS the repetition; an empty `{}` row stays a present row",
        )
        assertEquals(listOf(listOf(1, 1)), document.instantiatedRows("/Order/Shipping"))
        assertEquals(
            listOf(listOf(1, 1)),
            document.instantiatedRows("/Order/Attachments"),
            "a present-but-empty group is `{}` and instantiates its row",
        )

        assertEquals(StoredCellState.PresentValue("ORD-7"), document.cellStateAt("/Order/Ref", listOf(1, 1)))
        assertEquals(
            StoredCellState.PresentValue("250.00"),
            document.cellStateAt("/Order/Items/Qty", listOf(1, 1, 1)),
            "a native JSON number keeps its authored scale — the Kernel's `asText()` stringification",
        )
        assertEquals(StoredCellState.PresentValue("true"), document.cellStateAt("/Order/Express", listOf(1, 1)))
        assertEquals(StoredCellState.PresentValue("2026-03-01"), document.cellStateAt("/Order/Placed", listOf(1, 1)))
        assertEquals(StoredCellState.PresentEmpty, document.cellStateAt("/Order/Items/Note", listOf(1, 2, 1)))
        assertEquals(
            StoredCellState.Absent,
            document.cellStateAt("/Order/Items/Qty", listOf(1, 2, 1)),
            "a missing key is an omission, never an empty cell",
        )

        assertEquals(
            """{"Order":{"Express":true,"Placed":"2026-03-01","Ref":"ORD-7",""" +
                """"Attachments":{},"Items":[{"Qty":250.00},{"Note":""},{}],"Shipping":{"Mode":"express"}}}""",
            documents.encodeDocument(snapshot),
            "direct fields sorted by name, then subgroups sorted by name — the Kernel writer's key order",
        )
    }

    @Test
    fun readNormalizesEveryTypedValueTheWayA12sOwnConverterDoes() {
        val documents = prepared().documents
        // Every literal here is one measured row of `adapter … A12JsonIngressKernelTest`: A12 converts the
        // stringified JSON value to `String | Boolean | BigDecimal | Instant` and re-renders from the VALUE, so
        // a non-canonical spelling never survives ingestion. The transport reproduces that on its stored text.
        val json = """
            {"Order":{"Express":"TRUE","Signed":"True","Code":"007",
              "Items":[{"Qty":"1e5"},{"Qty":"+5"},{"Qty":"007"}]}}
        """.trimIndent()

        val document = documents.readDocument(json).document()

        assertEquals(
            StoredCellState.PresentValue("true"),
            document.cellStateAt("/Order/Express", listOf(1, 1)),
            "A12 parses a boolean with `\"true\".equalsIgnoreCase(value)` and writes `booleanNode`",
        )
        assertEquals(StoredCellState.PresentValue("true"), document.cellStateAt("/Order/Signed", listOf(1, 1)))
        assertEquals(
            StoredCellState.PresentValue("007"),
            document.cellStateAt("/Order/Code", listOf(1, 1)),
            "`leadingZerosAllowed` is A12's one number case that keeps the AUTHORED text (it stores a String)",
        )
        assertEquals(
            StoredCellState.PresentValue("100000"),
            document.cellStateAt("/Order/Items/Qty", listOf(1, 1, 1)),
            "scientific notation never survives: A12 stores the BigDecimal and every text it renders is plain",
        )
        assertEquals(
            StoredCellState.PresentValue("5"),
            document.cellStateAt("/Order/Items/Qty", listOf(1, 2, 1)),
            "a leading `+` parses for A12's converter and is gone from every rendering of the value",
        )
        assertEquals(
            StoredCellState.PresentValue("7"),
            document.cellStateAt("/Order/Items/Qty", listOf(1, 3, 1)),
            "leading zeros are NOT allowed on this field, so A12 converts and the zeros are gone",
        )
    }

    @Test
    fun aValueA12sConverterCannotParseStaysVerbatimAndAnEmptyTypedValueBecomesTheEmptyCell() {
        val documents = prepared().documents
        val json = """
            {"Order":{"Express":"yes","Signed":false,"Code":"","Placed":"01.03.2026",
              "Items":[{"Qty":"1,5","Note":""}]}}
        """.trimIndent()

        val snapshot = documents.readDocument(json)
        val document = snapshot.document()

        assertEquals(
            StoredCellState.PresentValue("yes"),
            document.cellStateAt("/Order/Express", listOf(1, 1)),
            "A12 keeps a value its converter rejects verbatim and writes it back as a string ([A12K-3638])",
        )
        assertEquals(
            StoredCellState.PresentValue("false"),
            document.cellStateAt("/Order/Signed", listOf(1, 1)),
            "a CONFIRM has no false: A12's `parseConfirm` accepts only `true`, so `false` is a pass-through",
        )
        assertEquals(
            StoredCellState.PresentValue("01.03.2026"),
            document.cellStateAt("/Order/Placed", listOf(1, 1)),
            "the date family is NOT normalized — a spelling A12's parser rejects is kept, one it accepts is " +
                "already the model format",
        )
        assertEquals(StoredCellState.PresentValue("1,5"), document.cellStateAt("/Order/Items/Qty", listOf(1, 1, 1)))
        assertEquals(
            StoredCellState.PresentEmpty,
            document.cellStateAt("/Order/Code", listOf(1, 1)),
            "A12 treats `\"\"` on a TYPED field like null — present with no value, written back as null",
        )
        // Both empties reach the same cell STATE (`""` is evaluation-empty here too); the difference is what
        // comes back out — `null` for the typed field A12 converted to null, `""` for the STRING it kept.
        assertEquals(StoredCellState.PresentEmpty, document.cellStateAt("/Order/Items/Note", listOf(1, 1, 1)))

        assertEquals(
            """{"Order":{"Code":null,"Express":"yes","Placed":"01.03.2026","Signed":"false",""" +
                """"Items":[{"Note":"","Qty":"1,5"}]}}""",
            documents.encodeDocument(snapshot),
            "an unconvertible value is written back as a string — A12's own pass-through shape",
        )
    }

    @Test
    fun encodeIsItsOwnFixedPointAcrossEveryValueState() {
        val documents = prepared().documents
        val json = """
            {"Order":{"Ref":null,"Items":[{"Qty":"not-a-number","Note":""},{"Qty":7}],"Shipping":{}}}
        """.trimIndent()

        val encoded = documents.encodeDocument(documents.readDocument(json))

        assertEquals(
            """{"Order":{"Ref":null,"Items":[{"Note":"","Qty":"not-a-number"},{"Qty":7}],"Shipping":{}}}""",
            encoded,
            "a valueless cell writes `null`; malformed number text writes back untouched as a string",
        )
        assertEquals(encoded, documents.encodeDocument(documents.readDocument(encoded)))
    }

    @Test
    fun numbersAreWrittenInA12sPlainFormWithTheAuthoredScaleIntact() {
        val documents = prepared().documents
        // A12 runs its generator with WRITE_BIGDECIMAL_AS_PLAIN, so it never emits scientific notation and
        // never drops a trailing zero. Reading normalizes the value the way A12's converter does, so the
        // stored text is already plain and writing it is the identity (KERNEL-FINDINGS "The canonical Document
        // JSON …"; the oracle is A12NumberPlainFormTest).
        val snapshot = documents.readDocument(
            """{"Order":{"Items":[{"Qty":250.00},{"Qty":1e5},{"Qty":1.2e-3}]}}""",
        )
        val document = snapshot.document()

        assertEquals(
            StoredCellState.PresentValue("250.00"),
            document.cellStateAt("/Order/Items/Qty", listOf(1, 1, 1)),
            "the authored scale survives — `toPlainString` keeps every trailing zero",
        )
        assertEquals(StoredCellState.PresentValue("100000"), document.cellStateAt("/Order/Items/Qty", listOf(1, 2, 1)))

        assertEquals(
            """{"Order":{"Items":[{"Qty":250.00},{"Qty":100000},{"Qty":0.0012}]}}""",
            documents.encodeDocument(snapshot),
        )
        // Text that A12's converter cannot parse stays a string — its own pass-through for a value it could
        // not convert — so a malformed value never becomes invalid JSON. A leading-zero value is NOT in that
        // class on an ordinary field: the converter parses it and the zeros are gone.
        assertEquals(
            """{"Order":{"Items":[{"Qty":7},{"Qty":"not-a-number"}]}}""",
            documents.encodeDocument(
                documents.readDocument("""{"Order":{"Items":[{"Qty":"007"},{"Qty":"not-a-number"}]}}"""),
            ),
        )
    }

    @Test
    fun aJsonNullIsAPresentValuelessCellNotAnOmission() {
        val document = prepared().documents.readDocument("""{"Order":{"Ref":null}}""").document()

        assertEquals(
            StoredCellState.PresentEmpty,
            document.cellStateAt("/Order/Ref", listOf(1, 1)),
            "the Kernel keeps a null-valued field as `FieldInstanceV2.ofValue(null)` — present, no value",
        )
        assertEquals(StoredCellState.Absent, document.cellStateAt("/Order/Placed", listOf(1, 1)))
    }

    @Test
    fun jsonRepetitionsCompactSurvivingObjectsIndependentlyAtEachParent() {
        val documents = prepared().documents
        val row = """{"Qty":7}"""
        val cases = listOf(
            "object" to row,
            "single" to "[$row]",
            "null-prefix" to "[null,$row]",
            "null-suffix" to "[$row,null]",
            "mixed" to "[null,7,[],true,\"skip\",$row]",
            "empty-row" to "[{},$row]",
            "two-survivors" to "[null,$row,false,$row]",
            "null-only" to "[null]",
        )
        for ((label, items) in cases) {
            val snapshot = documents.readDocument("""{"Order":{"Items":$items}}""")
            val expected = when (label) {
                "empty-row" -> "[{},$row]"
                "two-survivors" -> "[$row,$row]"
                "null-only" -> null
                else -> "[$row]"
            }
            assertEquals(if (expected == null) """{"Order":{}}""" else """{"Order":{"Items":$expected}}""",
                documents.encodeDocument(snapshot), label)
        }
        val nested = documents.readDocument("""{"Order":[null,{"Items":[null,$row]},false,{"Items":[null,{},$row]}]}""")
        assertEquals(StoredCellState.PresentValue("7"), nested.document().cellStateAt("/Order/Items/Qty", listOf(1, 1, 1)))
        assertEquals(StoredCellState.PresentValue("7"), nested.document().cellStateAt("/Order/Items/Qty", listOf(2, 2, 1)))
        assertEquals(listOf(listOf(1, 1), listOf(2, 1), listOf(2, 2)), nested.document().instantiatedRows("/Order/Items"))
    }

    @Test
    fun notificationOnlyNodesAreDiscardedWithoutLosingValidSiblings() {
        val documents = prepared().documents
        val json = """
            {"Order":{"Ref":"kept","Nowhere":{},"Express":{"nested":1},"Items":"not-an-array","Shipping":[null,{},7]}}
        """.trimIndent()
        assertEquals("""{"Order":{"Ref":"kept","Shipping":{}}}""", documents.encodeDocument(documents.readDocument(json)))
    }

    @Test
    fun malformedJsonAndANonObjectRootAreTheSoleDiagnostic() {
        val documents = prepared().documents
        for (source in listOf("""{"Order":}""", """["Order"]""", "7", "")) {
            val error = assertFailsWith<DocumentInputError>(source) { documents.readDocument(source) }
            val diagnostic = error.diagnostics.single()
            assertEquals(DocumentInputReasonCode.MALFORMED_JSON, diagnostic.reasonCode, source)
            assertNotNull(diagnostic.message, source)
        }
    }

    @Test
    fun theRootDocumentIdIsReadIgnoredForEvaluationAndWrittenBackAsText() {
        val documents = prepared().documents

        val snapshot = documents.readDocument("""{"id":"ORD-4711","Order":{"Ref":"a"}}""")

        assertEquals("ORD-4711", snapshot.documentId)
        assertEquals(
            """{"id":"ORD-4711","Order":{"Ref":"a"}}""",
            documents.encodeDocument(snapshot),
            "the serializer's root `id` is a direct field of the root object, so it sorts before the groups",
        )
        assertEquals(
            """{"id":"7","Order":{}}""",
            documents.encodeDocument(documents.readDocument("""{"id":7,"Order":{}}""")),
            "a numeric id is accepted and written as text, matching the Kernel's `fieldValue.toString()`",
        )
        assertEquals(
            """{"id":"1E+3","Order":{}}""",
            documents.encodeDocument(documents.readDocument("""{"id":1e3,"Order":{}}""")),
            "a numeric id is its node's text, as a String field's value is",
        )
        val error = assertFailsWith<DocumentInputError> { documents.readDocument("""{"id":true}""") }
        assertEquals(DocumentInputReasonCode.INVALID_DOCUMENT_ID, error.diagnostics.single().reasonCode)
    }

    @Test
    fun anOverRepeatedRowStaysRepresentableAndANonRepeatableExcessRowRefusesToEncode() {
        val documents = prepared().documents

        val overRepeated = documents.readDocument("""{"Order":{"Items":[{},{},{},{},{"Qty":4}]}}""")
        assertEquals(5, overRepeated.document().instantiatedRows("/Order/Items").size)
        assertEquals(
            """{"Order":{"Items":[{},{},{},{},{"Qty":4}]}}""",
            documents.encodeDocument(overRepeated),
            "a coordinate above the declared maximum survives the round trip — formal validation reports it",
        )

        // A12's reader accepts an array at ANY group (`DocumentV2JsonReader.repetitionsFromJson`), so a
        // non-repeatable group's second row stays READABLE; A12's writer has no shape for it.
        val excess = documents.readDocument("""{"Order":{"Shipping":[{},{"Mode":"courier"}]}}""")
        assertEquals(2, excess.document().instantiatedRows("/Order/Shipping").size)
        val refusal = assertFailsWith<DocumentEncodeError> { documents.encodeDocument(excess) }
        assertEquals("/Order/Shipping", refusal.modelPath)
        assertEquals("Order/Shipping[2]", refusal.pointer)
        assertEquals("A12_DOCUMENT_NOT_REPRESENTABLE", refusal.code)
    }

    @Test
    fun eachDecodeLimitFailsFastWithItsTypedResource() {
        val documents = prepared().documents
        val cases = listOf(
            Triple(
                DocumentDecodeLimits.builder().maxDocumentCodeUnits(8).build(),
                """{"Order":{"Ref":"a"}}""",
                DocumentInputResource.DOCUMENT_CODE_UNITS,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxGroupPlacements(1).build(),
                """{"Order":{"Items":[{},{}]}}""",
                DocumentInputResource.GROUP_PLACEMENTS,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxFieldPlacements(1).build(),
                """{"Order":{"Ref":"a","Placed":"2026-03-01"}}""",
                DocumentInputResource.FIELD_PLACEMENTS,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxPathCodeUnits(8).build(),
                """{"Order":{"Ref":"a"}}""",
                DocumentInputResource.PATH_CODE_UNITS,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxRawCodeUnitsPerField(3).build(),
                """{"Order":{"Ref":"abcd"}}""",
                DocumentInputResource.RAW_CODE_UNITS_PER_FIELD,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxTotalRawCodeUnits(5).build(),
                """{"Order":{"Ref":"abc","Shipping":{"Mode":"def"}}}""",
                DocumentInputResource.TOTAL_RAW_CODE_UNITS,
            ),
            Triple(
                DocumentDecodeLimits.builder().maxModelNestingDepth(1).build(),
                """{"Order":{"Ref":"a"}}""",
                DocumentInputResource.MODEL_NESTING_DEPTH,
            ),
        )

        for ((limits, json, resource) in cases) {
            val error = assertFailsWith<DocumentInputError>(resource.name) {
                documents.readDocument(json, limits)
            }
            val diagnostic = error.diagnostics.single()
            assertEquals(DocumentInputReasonCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.reasonCode, resource.name)
            assertEquals(resource, diagnostic.resource, resource.name)
            assertNotNull(diagnostic.maximum, resource.name)
            assertNotNull(diagnostic.actual, resource.name)
        }
        // The touched instance stays untouched by the failing sibling cases above.
        documents.readDocument("{}")
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
    fun encodeRefusesForeignAndStaleSnapshotsByOwnerIdentity() {
        val first = prepared()
        val second = prepared()
        val snapshot = first.documents.readDocument("""{"Order":{}}""")

        assertSame(first.owner, snapshot.owner)
        assertSame(first.modelVersion, snapshot.modelVersion)
        first.documents.encodeDocument(snapshot)

        val stale = assertFailsWith<ModelOwnerMismatchError> { second.documents.encodeDocument(snapshot) }
        assertSame(second.owner, stale.expected)
        assertSame(first.owner, stale.actual)

        val foreign = PreparedModel.loadExpandedJson(foreignModel())
        assertFailsWith<ModelOwnerMismatchError> { foreign.documents.encodeDocument(snapshot) }
    }

    @Test
    fun seedDocumentBuildsACompleteModelShapedCanonicalCandidate() {
        val prepared = prepared()

        val snapshot = prepared.documents.seedDocument()
        val document = snapshot.document()
        val placements = document.placementsSnapshot()

        assertEquals(
            prepared.definition().groupPaths,
            placements.filter { it.isGroup }.map { it.path }.toSet(),
            "the seed carries every declared group, including a fieldless non-repeatable group",
        )
        assertEquals(
            prepared.definition().fields.map { it.path }.toSet(),
            placements.filterNot { it.isGroup }.map { it.path }.toSet(),
            "the seed carries every declared field",
        )
        assertEquals(
            listOf(listOf(1, 1)),
            document.instantiatedRows("/Order/Items"),
            "the authoring seed instantiates one row of each repeatable group",
        )

        val encoded = prepared.documents.encodeDocument(snapshot)
        assertEquals(
            encoded,
            prepared.documents.encodeDocument(prepared.documents.readDocument(encoded)),
            "the seed is A12's canonical Document JSON, not a showcase-only shape",
        )
    }

    @Test
    fun anEmptyDocumentReadsAndWritesAsTheEmptyRootObject() {
        val documents = prepared().documents
        val snapshot = documents.readDocument("{}")

        assertTrue(snapshot.document().valuedFields().isEmpty())
        assertEquals("{}", documents.encodeDocument(snapshot))
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
                      "type":"Field","id":"/Order/Express","name":"Express",
                      "Field":{"fieldType":{"type":"BooleanType"}}
                    },
                    {
                      "type":"Field","id":"/Order/Placed","name":"Placed",
                      "Field":{"fieldType":{"type":"DateType"}}
                    },
                    {
                      "type":"Field","id":"/Order/Signed","name":"Signed",
                      "Field":{"fieldType":{"type":"ConfirmType"}}
                    },
                    {
                      "type":"Field","id":"/Order/Code","name":"Code",
                      "Field":{"fieldType":{"type":"NumberType","NumberType":{"leadingZerosAllowed":true}}}
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
