package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.StoredCellState
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.eval.toDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The **model-level** A12 Document JSON codec — `ModelDef.readDocumentJson` / `Document.toDocumentJson`, the
 * pair that sits beneath the owner-bound [PreparedModel.documents] boundary, at the same level as the already
 * public `ModelDef.newDocument()`. It exists so an in-repo consumer holding a
 * [io.github.mbackschat.a12.dm.interpreter.model.ModelDef] (dmtool does, for every runtime verb) speaks the
 * canonical shape through **this** implementation instead of writing a second one — the duplication CLAUDE.md
 * "NEVER invent a shape A12 already defines" forbids. It is deliberately NOT in the root-package façade: that
 * is the released, ABI-frozen contract, and these engine packages are not.
 *
 * These tests assert the pair IS the same codec [DocumentTransportTest] locks on the high-level surface, not a
 * re-test of the format: the shape, the value normalization, and the resource ceilings must all be reached
 * through it.
 */
class DocumentJsonCodecTest {

    @Test
    fun theModelLevelPairReadsAndWritesTheSameCanonicalShapeAsTheOwnerBoundBoundary() {
        val model = ModelLoader.load(ORDER_MODEL)
        val json = """{"Order":{"Ref":"ORD-7","Qty":250.00,"Items":[{"Note":""},{}]}}"""

        val document = model.readDocumentJson(json)

        assertEquals(StoredCellState.PresentValue("ORD-7"), document.cellStateAt("/Order/Ref", listOf(1, 1)))
        assertEquals(
            StoredCellState.PresentValue("250.00"),
            document.cellStateAt("/Order/Qty", listOf(1, 1)),
            "a native JSON number keeps its authored scale",
        )
        assertEquals(listOf(listOf(1, 1), listOf(1, 2)), document.instantiatedRows("/Order/Items"))
        assertEquals(
            """{"Order":{"Qty":250.00,"Ref":"ORD-7","Items":[{"Note":""},{}]}}""",
            document.toDocumentJson(model),
            "direct fields sorted by name then subgroups sorted by name, and no wrapper — a STRING keeps its " +
                "`\"\"` where a TYPED kind's empty value would be written `null`",
        )
    }

    @Test
    fun readingThroughTheModelLevelPairNormalizesValuesTheWayA12sConverterDoes() {
        val model = ModelLoader.load(ORDER_MODEL)

        // One measured row each of `adapter … A12JsonIngressKernelTest`: A12 converts the stringified node and
        // re-renders from the stored VALUE, so a non-canonical spelling never survives ingestion. A CLI reading
        // `--instance` through this pair therefore cannot report a formal error A12 itself would not report.
        val document = model.readDocumentJson("""{"Order":{"Qty":"1e5","Flag":"TRUE"}}""")

        assertEquals(StoredCellState.PresentValue("100000"), document.cellStateAt("/Order/Qty", listOf(1, 1)))
        assertEquals(StoredCellState.PresentValue("true"), document.cellStateAt("/Order/Flag", listOf(1, 1)))
    }

    @Test
    fun theModelLevelReadHonoursTheSuppliedResourceCeilings() {
        val model = ModelLoader.load(ORDER_MODEL)
        val limits = DocumentDecodeLimits.builder().maxFieldPlacements(1).build()

        val failure = assertFailsWith<DocumentInputError> {
            model.readDocumentJson("""{"Order":{"Ref":"a","Qty":"1"}}""", limits)
        }

        assertEquals(
            listOf(DocumentInputReasonCode.RESOURCE_LIMIT_EXCEEDED),
            failure.diagnostics.map { it.reasonCode },
        )
    }

    @Test
    fun theModelLevelReadDefaultsItsCeilingsSoTheOneArgumentFormIsSafe() {
        val model = ModelLoader.load(ORDER_MODEL)

        // The same document that exceeds a hand-tightened ceiling above must pass on the defaults.
        assertEquals(
            """{"Order":{"Qty":1,"Ref":"a"}}""",
            model.readDocumentJson("""{"Order":{"Ref":"a","Qty":"1"}}""").toDocumentJson(model),
        )
    }

    @Test
    fun aDocumentBuiltFieldByFieldEncodesThroughTheSameWriter() {
        val model = ModelLoader.load(ORDER_MODEL)

        // This is `dmtool model seed`'s path: the generator fills a Document, the codec writes it. A repeatable
        // group is an ARRAY because it is DECLARED repeatable, never because more than one row was populated.
        val document = model.newDocument()
            .field("/Order/Ref", "ORD-1", 1, 1)
            .group("/Order/Items", 1, 1)
            .field("/Order/Items/Note", "first", 1, 1, 1)
            .emptyField("/Order/Qty", 1, 1)

        assertEquals(
            """{"Order":{"Qty":null,"Ref":"ORD-1","Items":[{"Note":"first"}]}}""",
            document.toDocumentJson(model),
        )
    }

    @Test
    fun explicitSparsePlacementStillPadsPredecessorsWhileJsonCompactsNonObjects() {
        val model = ModelLoader.load(ORDER_MODEL)
        val path = "/Order/Items/Note"
        val placed = model.newDocument().field(path, "third", 1, 3, 1)
        assertEquals("""{"Order":{"Items":[{},{},{"Note":"third"}]}}""", placed.toDocumentJson(model))
        val read = model.readDocumentJson("""{"Order":{"Items":[null,null,{"Note":"third"}]}}""")
        assertEquals("""{"Order":{"Items":[{"Note":"third"}]}}""", read.toDocumentJson(model))
    }

    @Test
    fun theRootIdIsValidatedOnReadAndBelongsToTheOwnerBoundSurfaceAlone() {
        val model = ModelLoader.load(ORDER_MODEL)

        // Accepted (never an unknown-path defect) but not carried: this pair has nowhere to keep it, so a
        // consumer that needs the document identity uses `PreparedModel.documents` instead.
        val document = model.readDocumentJson("""{"id":"doc-1","Order":{"Ref":"a"}}""")

        assertEquals("""{"Order":{"Ref":"a"}}""", document.toDocumentJson(model))
        assertEquals("""{"Order":{"Ref":"a"}}""",
            model.readDocumentJson("""{"id":[1],"Order":{"Ref":"a"}}""").toDocumentJson(model))
    }

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
                      "type":"Field","id":"/Order/Qty","name":"Qty",
                      "Field":{"fieldType":{"type":"NumberType","NumberType":{"maxFractionalDigits":2}}}
                    },
                    {
                      "type":"Field","id":"/Order/Flag","name":"Flag",
                      "Field":{"fieldType":{"type":"BooleanType"}}
                    },
                    {
                      "type":"Group","id":"/Order/Items","name":"Items",
                      "Group":{"repeatability":5,"elements":[{
                        "type":"Field","id":"/Order/Items/Note","name":"Note",
                        "Field":{"fieldType":{"type":"StringType"}}
                      }]}
                    }
                  ]}
                }]}
              }
            }
        """.trimIndent()
    }
}
