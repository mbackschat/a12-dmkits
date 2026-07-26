package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.load.InvalidPreparedModelSourceException
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.load.UnpreparedModelResidueException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Multiplatform (JVM + JS) lock for IF145's enforced preparation boundary: [ModelLoader] consumes only an
 * EXPANDED, self-contained, name-path-keyed model, so a raw model that slips past preparation must fail LOUD
 * — never load as a structurally plausible but wrong model. Every CLI consumer prepares upstream via
 * `ModelReader.expand`; this proves the loader itself refuses the complete known residue family rather than
 * degrading silently.
 */
class ModelLoaderPreparedGuardTest {

    /** A model with `content` wrapping the given root-group elements (name-path ids unless overridden). */
    private fun model(elements: String) = """
        {
          "header": { "id": "policy" },
          "content": { "modelRoot": { "rootGroups": [ {
            "type": "Group", "id": "/Policy", "name": "Policy", "Group": { "elements": [ $elements ] } } ] } }
        }
    """.trimIndent()

    @Test fun aPreparedModelLoads() {
        val def = ModelLoader.load(model(
            """{ "type": "Field", "id": "/Policy/Ref", "name": "Ref", "Field": { "fieldType": {"type":"StringType"} } }"""))
        assertEquals(1, def.fields.size, "the clean prepared model loads its one field")
    }

    @Test
    fun everyUnexpandedIncludeMemberIsRejectedEvenWhenItsValueIsNull() {
        for (member in listOf("modelAlias", "includeLevel", "excludeRules", "excludeComputations")) {
            val raw = model(
                """
                    {
                      "type": "Group",
                      "id": "/Policy/Cover",
                      "name": "Cover",
                      "Group": {"repeatability": 1, "$member": null}
                    }
                """.trimIndent(),
            )

            val error = assertFailsWith<UnpreparedModelResidueException> {
                ModelLoader.load(raw)
            }

            assertEquals("include", error.residue, member)
            assertEquals("/Policy/Cover", error.path, member)
            assertContains(error.message ?: "", member)
        }
    }

    @Test
    fun unresolvedHeaderModelReferencesAreRejected() {
        val raw = model(
            """{"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType"}}}""",
        ).replace(
            """"header": { "id": "policy" }""",
            """"header": {"id":"policy","modelReferences":[{"modelId":"shared"}]}""",
        )

        val error = assertFailsWith<UnpreparedModelResidueException> {
            ModelLoader.load(raw)
        }

        assertEquals("model-reference", error.residue)
        assertEquals("header.modelReferences", error.path)
    }

    @Test
    fun nullModelReferencesRemainLegalButOtherNonArrayContainersAreRejected() {
        val clean = model(
            """{"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType"}}}""",
        )
        val withNullReferences = clean.replace(
            """"header": { "id": "policy" }""",
            """"header": {"id":"policy","modelReferences":null}""",
        )

        ModelLoader.load(withNullReferences)

        for (invalid in listOf("true", "7", "\"not-references\"", "{}")) {
            val malformed = clean.replace(
                """"header": { "id": "policy" }""",
                """"header": {"id":"policy","modelReferences":$invalid}""",
            )
            val error = assertFailsWith<InvalidPreparedModelSourceException> {
                ModelLoader.load(malformed)
            }

            assertEquals("header.modelReferences", error.path, invalid)
        }
    }

    @Test
    fun anUnresolvedTypeDefIsRejected() {
        val raw = model(
            """{ "type": "Field", "id": "/Policy/Code", "name": "Code",
                 "Field": { "fieldType": { "type": "TypeDefType", "TypeDefType": { "typeDefinitionId": "CodeDef" } } } }""")
        val error = assertFailsWith<UnpreparedModelResidueException> {
            ModelLoader.load(raw)
        }

        assertEquals("type-definition", error.residue)
        assertEquals("/Policy/Code", error.path)
    }

    @Test
    fun anOpaqueIdIsRejected() {
        // A real kernel model carries opaque hash ids; the loader addresses elements by name-path, so a
        // non-`/`-rooted id must be normalized upstream, not trusted verbatim.
        val raw = model(
            """{ "type": "Field", "id": "f7a3c9hash", "name": "Ref", "Field": { "fieldType": {"type":"StringType"} } }""")
        val error = assertFailsWith<UnpreparedModelResidueException> {
            ModelLoader.load(raw)
        }

        assertEquals("opaque-id", error.residue)
        assertEquals("f7a3c9hash", error.path)
    }

    @Test
    fun malformedElementRecordsAreRejectedInsteadOfBeingDroppedOrDefaulted() {
        val cases = listOf(
            InvalidElementCase(
                """{"type":"UnknownElement","id":"/Policy/Unknown","name":"Unknown","UnknownElement":{}}""",
                "/Policy/Unknown",
            ),
            InvalidElementCase(
                """{"type":"Field","name":"Ref","Field":{"fieldType":{"type":"StringType"}}}""",
                "/Policy/Ref",
            ),
            InvalidElementCase(
                """{"type":"Field","id":7,"name":"Ref","Field":{"fieldType":{"type":"StringType"}}}""",
                "/Policy/Ref",
            ),
            InvalidElementCase(
                """{"type":"Field","id":"/Policy/Ref","Field":{"fieldType":{"type":"StringType"}}}""",
                "/Policy/Ref",
            ),
            InvalidElementCase(
                """{"type":"Field","id":"/Policy/Ref","name":7,"Field":{"fieldType":{"type":"StringType"}}}""",
                "/Policy/Ref",
            ),
            InvalidElementCase(
                """{"type":"Field","id":"/Policy/Ref","name":"Ref","Field":null}""",
                "/Policy/Ref",
            ),
            InvalidElementCase(
                """{"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"UnknownFieldType"}}}""",
                "/Policy/Ref",
            ),
            InvalidElementCase(
                """{"type":"Group","id":"/Policy/Nested","name":"Nested","Group":null}""",
                "/Policy/Nested",
            ),
            InvalidElementCase(
                """{"type":"Rule","id":"/Policy/Rule","name":"Rule","Rule":null}""",
                "/Policy/Rule",
            ),
            InvalidElementCase(
                """{"type":"Computation","id":"/Policy/Calc","name":"Calc","Computation":null}""",
                "/Policy/Calc",
            ),
        )

        for (case in cases) {
            val error = assertFailsWith<InvalidPreparedModelSourceException> {
                ModelLoader.load(model(case.element))
            }

            assertEquals(case.path, error.path, case.element)
        }
    }

    @Test
    fun elementRecordsOutsideTheModelTreeAreRejectedInsteadOfBecomingModelContent() {
        val raw = model(
            """{"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType"}}}""",
        ).replace(
            """"header": { "id": "policy" }""",
            """
                "header": {
                  "id": "policy",
                  "unrecognizedElement": {
                    "type":"Field",
                    "id":"/Phantom",
                    "name":"Phantom",
                    "Field":{"fieldType":{"type":"StringType"}}
                  }
                }
            """.trimIndent(),
        )

        val error = assertFailsWith<InvalidPreparedModelSourceException> {
            ModelLoader.load(raw)
        }

        assertEquals("header.unrecognizedElement", error.path)
    }

    @Test
    fun elementRecordsHiddenInsideARealElementBodyAreAlsoRejected() {
        val raw = model(
            """
                {
                  "type":"Field",
                  "id":"/Policy/Ref",
                  "name":"Ref",
                  "Field":{
                    "fieldType":{"type":"StringType"},
                    "unrecognizedElement":{
                      "type":"Field",
                      "id":"/Phantom",
                      "name":"Phantom",
                      "Field":{"fieldType":{"type":"StringType"}}
                    }
                  }
                }
            """.trimIndent(),
        )

        val error = assertFailsWith<InvalidPreparedModelSourceException> {
            ModelLoader.load(raw)
        }

        assertEquals(
            "/Policy/Ref.Field.unrecognizedElement",
            error.path,
        )
    }

    @Test
    fun malformedDatePrecisionIsRejectedInsteadOfDefaultingToFull() {
        for (precision in listOf("null", "\"UNKNOWN\"", "true", "7", "{}", "[]")) {
            val raw = model(
                """
                    {
                      "type":"Field",
                      "id":"/Policy/ObservedOn",
                      "name":"ObservedOn",
                      "Field":{"fieldType":{
                        "type":"DateType",
                        "DateType":{"format":"dd.MM.yyyy","datePrecision":$precision}
                      }}
                    }
                """.trimIndent(),
            )

            val error = assertFailsWith<InvalidPreparedModelSourceException> {
                ModelLoader.load(raw)
            }

            assertEquals("/Policy/ObservedOn", error.path, precision)
        }
    }

    @Test
    fun computationTargetCannotWalkAboveTheModelRoot() {
        val raw = model(
            """
                {
                  "type":"Computation",
                  "id":"/Policy/Calc",
                  "name":"Calc",
                  "Computation":{
                    "computedFieldRelPath":"../../../Value",
                    "computationAlternatives":[]
                  }
                }
            """.trimIndent(),
        )

        val error = assertFailsWith<InvalidPreparedModelSourceException> {
            ModelLoader.load(raw)
        }

        assertEquals("/Policy/Calc", error.path)
    }

    @Test
    fun aPreparedModelRequiresTheKernelRequiredContentContainer() {
        val error = assertFailsWith<InvalidPreparedModelSourceException> {
            ModelLoader.load("""{"header":{"id":"policy"}}""")
        }

        assertEquals("content", error.path)
    }

    private data class InvalidElementCase(
        val element: String,
        val path: String,
    )
}
