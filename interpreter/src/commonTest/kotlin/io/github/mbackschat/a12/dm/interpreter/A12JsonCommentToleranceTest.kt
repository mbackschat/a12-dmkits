package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.eval.toDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * **A12 accepts JSON comments — in a model source and in a document alike — so every A12-shape parse boundary
 * here accepts them too** (CLAUDE.md "…and adopt A12's JSON I/O BEHAVIOUR exactly, not just its shape").
 *
 * Source basis: `ObjectMapperProvider` builds both of A12's mappers with `JsonParser.Feature.ALLOW_COMMENTS` —
 * `createPreconfiguredObjectMapper` (the model serializer's) and `createObjectMapperForDocSerializer` (the
 * document serializer's). A comment is therefore not "tolerated input" we may reject at our leisure; a commented
 * model or document is a **legal A12 artifact**, and refusing it makes a file A12 reads unreadable here.
 *
 * **And no further than that.** Neither mapper enables `ALLOW_TRAILING_COMMA`, so a trailing comma stays a parse
 * error — matching A12's ingress means matching its strictness, not being generally lenient. That row is what
 * makes this class a parity lock rather than a "be forgiving" preference.
 *
 * The sites are covered as a **class**, not one reported symptom: the document codec, the standalone
 * [ModelLoader], and workspace preparation (whose own admission/assembly/collection parses are the sites a
 * per-call-site fix would have missed).
 */
class A12JsonCommentToleranceTest {

    @Test
    fun aCommentedDocumentReadsExactlyLikeTheUncommentedOne() {
        val model = ModelLoader.load(ORDER_MODEL)

        val document = model.readDocumentJson(
            """
            {
              // the order this line comments on
              "Order": {
                "Ref": "ORD-7",
                /* a block comment, between two members */
                "Qty": 250.00
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            """{"Order":{"Qty":250.00,"Ref":"ORD-7"}}""",
            document.toDocumentJson(model),
            "the comments are concrete syntax: they vanish, and every value survives untouched",
        )
    }

    @Test
    fun aCommentedModelSourceLoadsThroughTheStandaloneLoader() {
        val commented = ModelLoader.load(COMMENTED_ORDER_MODEL)

        assertEquals(ModelLoader.load(ORDER_MODEL), commented)
    }

    @Test
    fun aCommentedModelSourceLoadsThroughWorkspacePreparation() {
        // Admission reads `header.id` by parsing the source, assembly and collection re-parse it, and each was a
        // separate default-`Json` call site. A commented source must survive all of them.
        val prepared = ModelWorkspace.fromSources(listOf(source(COMMENTED_ORDER_MODEL))).prepare("orders")

        assertEquals(
            ModelWorkspace.fromSources(listOf(source(ORDER_MODEL))).prepare("orders").definition(),
            prepared.definition(),
        )
    }

    @Test
    fun aTrailingCommaIsStillRefusedBecauseA12DoesNotEnableThatLeniency() {
        val model = ModelLoader.load(ORDER_MODEL)

        val failure = assertFailsWith<DocumentInputError> {
            model.readDocumentJson("""{"Order":{"Ref":"ORD-7",}}""")
        }
        assertEquals(listOf(DocumentInputReasonCode.MALFORMED_JSON), failure.diagnostics.map { it.reasonCode })

        assertFailsWith<IllegalArgumentException> {
            ModelLoader.load(ORDER_MODEL.replace(""""maxFractionalDigits":2}""", """"maxFractionalDigits":2,}"""))
        }
    }

    /**
     * kotlinx reads an unquoted token as a literal even in strict mode; A12's parser reads only `true`, `false`,
     * `null` and RFC 8259 numbers (KERNEL-FINDINGS KF593, `JsonLiteralStrictnessDiffTest`). `-0` and `1E1` are the
     * controls that each boundary still reads a number in that place.
     */
    @Test
    fun anUnquotedTokenThatIsNoJsonLiteralIsRefusedAtEveryBoundary() {
        val model = ModelLoader.load(ORDER_MODEL)
        for (token in listOf("abc", "True", "NaN", "0x10", "01", "1.", ".5", "+1")) {
            val failure = assertFailsWith<DocumentInputError>(token) { model.readDocumentJson("""{"Order":{"Qty":$token}}""") }
            assertEquals(listOf(DocumentInputReasonCode.MALFORMED_JSON), failure.diagnostics.map { it.reasonCode }, token)
            val source = ORDER_MODEL.replace(""""maxFractionalDigits":2}""", """"maxFractionalDigits":2,"maxValue":$token}""")
            assertFailsWith<IllegalArgumentException>(token) { ModelLoader.load(source) }
            assertFailsWith<ModelPreparationError>(token) { ModelWorkspace.fromSources(listOf(source(source))).prepare("orders") }
        }
        for (token in listOf("-0", "1E1")) {
            model.readDocumentJson("""{"Order":{"Qty":$token}}""")
            val source = ORDER_MODEL.replace(""""maxFractionalDigits":2}""", """"maxFractionalDigits":2,"maxValue":$token}""")
            ModelLoader.load(source)
            ModelWorkspace.fromSources(listOf(source(source))).prepare("orders")
        }
    }

    private fun source(json: String): WorkspaceModelSource =
        WorkspaceModelSource.builder().json(json).build()

    private companion object {
        val ORDER_MODEL = """
            {
              "header":{"id":"orders","modelVersion":"28.4.0","locales":[{"code":"en_US"}]},
              "content":{"modelInfo":{"name":"orders"},
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
                    }
                  ]}
                }]}
              }
            }
        """.trimIndent()

        /** The same model, commented the way a hand-maintained A12 model source is. */
        val COMMENTED_ORDER_MODEL = """
            {
              // the model this workspace prepares
              "header":{"id":"orders","modelVersion":"28.4.0","locales":[{"code":"en_US"}]},
              "content":{"modelInfo":{"name":"orders"},
                "modelRoot":{"rootGroups":[{
                  "type":"Group","id":"/Order","name":"Order",
                  "Group":{"elements":[
                    {
                      "type":"Field","id":"/Order/Ref","name":"Ref",
                      "Field":{"fieldType":{"type":"StringType"}}
                    },
                    /* the amount, two fractional digits by house rule */
                    {
                      "type":"Field","id":"/Order/Qty","name":"Qty",
                      "Field":{"fieldType":{"type":"NumberType","NumberType":{"maxFractionalDigits":2}}}
                    }
                  ]}
                }]}
              }
            }
        """.trimIndent()
    }
}
