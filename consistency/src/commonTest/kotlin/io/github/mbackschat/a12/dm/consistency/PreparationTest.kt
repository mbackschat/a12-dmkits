package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B3's minimum for references: the declared element set and the one model-config flag reference resolution
 * needs, both READ from the model rather than reconstructed.
 *
 * A12 gives every element an `id` that already IS its canonical path, and declares its kind twice — as the
 * `type` discriminator and as the key of the envelope holding the kind's own payload. The kernel's
 * `ElementDeserializer` reads `type` first and then looks the envelope up BY that value, so `type` is the
 * discriminator and envelope-by-elimination is not the kernel's mechanism (KERNEL-INTEGRATION-NOTES "A model
 * element declares its kind twice").
 */
class PreparationTest {

    private fun prepared(json: String): PreparedModel =
        prepare(assertIs<Admission.Admitted>(admit(ModelSource("order", json))))

    private val twoLevelModel = """
        {"header":{"id":"order"},"content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"/Order","name":"Order","Group":{"repeatability":1,"elements":[
            {"type":"Field","id":"/Order/Code","name":"Code","Field":{}},
            {"type":"Group","id":"/Order/Lines","name":"Lines","Group":{"repeatability":9,"elements":[
              {"type":"Field","id":"/Order/Lines/Qty","name":"Qty","Field":{}}
            ]}}
          ]}}
        ]}}}
    """.trimIndent()

    @Test
    fun preparationCollectsEveryDeclaredElementWithItsPathAndAuthoredKind() {
        val prepared = prepared(twoLevelModel)

        assertEquals(
            listOf("/Order", "/Order/Code", "/Order/Lines", "/Order/Lines/Qty"),
            prepared.elements.map { it.path },
        )
        assertEquals(
            listOf("Group", "Field", "Group", "Field"),
            prepared.elements.map { it.kind },
        )
    }

    /**
     * The discriminating case against building paths from the tree walk: an element whose `name` differs from
     * its `id`'s last segment. Concatenating names would report `/Order/Renamed` and resolve every reference
     * to it wrongly.
     */
    @Test
    fun theDeclaredPathComesFromIdRatherThanFromConcatenatedNames() {
        val field = prepared(
            """
            {"header":{"id":"order"},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
                {"type":"Field","id":"/Order/Code","name":"Renamed","Field":{}}
              ]}}
            ]}}}
            """.trimIndent(),
        ).elements.single { it.kind == "Field" }

        assertEquals("/Order/Code", field.path)
        assertEquals("Renamed", field.name)
    }

    /**
     * The kind envelope is optional: `ElementDeserializer` default-constructs the element when the envelope
     * keyed by `type` is absent, so a declaration carrying only its discriminator and identity is one the
     * kernel accepts and the checker must therefore see too.
     */
    @Test
    fun anElementWithoutItsKindEnvelopeIsStillADeclaredElement() {
        val prepared = prepared(
            """
            {"header":{"id":"order"},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
                {"type":"Field","id":"/Order/Code","name":"Code"}
              ]}}
            ]}}}
            """.trimIndent(),
        )

        assertEquals(listOf("/Order", "/Order/Code"), prepared.elements.map { it.path })
    }

    @Test
    fun preparationReadsWhetherTheModelAllowsShortNameFieldReferences() {
        fun flagOf(modelConfig: String) = prepared(
            "{\"header\":{\"id\":\"order\"},\"content\":{" + modelConfig +
                "\"modelRoot\":{\"rootGroups\":[]}}}",
        ).fieldRefByShortNameAllowed

        assertTrue(flagOf("\"modelConfig\":{\"fieldRefByShortNameAllowed\":true},"))
        assertFalse(flagOf("\"modelConfig\":{\"fieldRefByShortNameAllowed\":false},"))
        assertFalse(flagOf(""), "an absent modelConfig cannot enable the fallback")
    }
}
