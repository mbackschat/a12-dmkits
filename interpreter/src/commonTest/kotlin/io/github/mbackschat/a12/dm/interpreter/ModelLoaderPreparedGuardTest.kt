package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Multiplatform (JVM + JS) lock for IG91's enforced preparation boundary: [ModelLoader] consumes only an
 * EXPANDED, self-contained, name-path-keyed model, so a raw model that slips past preparation must fail LOUD
 * — never load as a structurally plausible but wrong model (a dropped mount, a typedef silently flattened to
 * STRING, an opaque id trusted as a path). Every CLI consumer prepares upstream via `ModelReader.expand`; this
 * proves the loader itself refuses the three unprepared-residue classes rather than degrade silently.
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

    @Test fun anUnexpandedIncludeIsRejected() {
        val raw = model(
            """{ "type": "Group", "id": "/Policy/Cover", "name": "Cover", "Group": { "repeatability": 1, "modelAlias": "benefits" } }""")
        val e = assertFailsWith<IllegalArgumentException> { ModelLoader.load(raw) }
        assertContains(e.message ?: "", "include", ignoreCase = true)
        assertContains(e.message ?: "", "/Policy/Cover")
    }

    @Test fun anUnresolvedTypeDefIsRejected() {
        val raw = model(
            """{ "type": "Field", "id": "/Policy/Code", "name": "Code",
                 "Field": { "fieldType": { "type": "TypeDefType", "TypeDefType": { "typeDefinitionId": "CodeDef" } } } }""")
        val e = assertFailsWith<IllegalArgumentException> { ModelLoader.load(raw) }
        assertContains(e.message ?: "", "TypeDefType")
        assertContains(e.message ?: "", "/Policy/Code")
    }

    @Test fun anOpaqueIdIsRejected() {
        // A real kernel model carries opaque hash ids; the loader addresses elements by name-path, so a
        // non-`/`-rooted id must be normalized upstream, not trusted verbatim.
        val raw = model(
            """{ "type": "Field", "id": "f7a3c9hash", "name": "Ref", "Field": { "fieldType": {"type":"StringType"} } }""")
        val e = assertFailsWith<IllegalArgumentException> { ModelLoader.load(raw) }
        assertContains(e.message ?: "", "name-path", ignoreCase = true)
        assertContains(e.message ?: "", "f7a3c9hash")
    }
}
