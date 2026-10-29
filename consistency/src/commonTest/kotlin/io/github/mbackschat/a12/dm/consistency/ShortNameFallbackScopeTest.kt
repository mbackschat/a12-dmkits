package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The bare-leaf short-name fallback sees only RESOLVABLE elements, so a variant-deleted field is invisible.
 *
 * **Why this exists: the code says its own control was missing.** `PreparedModel.resolve`'s fallback carries
 * a note that the element list is "named ONCE and then narrowed, rather than as two arms each naming the
 * list", because "one arm can silently stop honouring the variant deletion while the other keeps it, and a
 * mutant doing exactly that survived every control here (2026-09-13)". No test in this module paired variant
 * deletion with the short-name fallback, so that mutant had nothing to kill it. This is that control.
 *
 * **A within-model pair, not two models.** The same source is built twice differing in exactly one thing —
 * whether the single `Total` field carries `variantFilter.filterType = "ONLY"` — so nothing else can explain
 * the difference. Without the positive arm the negative one passes on any model where the name simply is not
 * there, which is the vacuity this whole family of tests keeps tripping over.
 */
class ShortNameFallbackScopeTest {

    private fun model(variantFilter: String) = ModelSource(
        """
        {"header":{"modelVersion":"28.4.0","id":"shortname","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelConfig":{"fieldRefByShortNameAllowed":true},
          "modelRoot":{"rootGroups":[
           {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
             {"type":"Field","id":"f0","name":"Total","Field":{"fieldType":{"type":"NumberType"}$variantFilter}},
             {"type":"Group","id":"g1","name":"Lines","Group":{"elements":[
               {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
             ]}}
           ]}}
          ]}}}
        """.trimIndent(),
    )

    private fun prepared(variantFilter: String): PreparedModel {
        val admitted = admit(model(variantFilter))
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible for a reference to be resolved")
        return prepare(admitted)
    }

    @Test
    fun theFallbackResolvesAFieldOutsideTheDeclaringGroupWhenTheFlagIsOn() {
        val reference = prepared(variantFilter = "").resolve(DECLARING_GROUP, "Total")

        assertEquals(
            Reference.Resolved("/Order/Total"),
            reference,
            "the positive arm must resolve, or the deletion arm below proves only that the name is absent",
        )
    }

    @Test
    fun theFallbackCannotSeeAFieldDeletedAtTheDefaultVariant() {
        val reference = prepared(variantFilter = ""","variantFilter":{"filterType":"ONLY"}""")
            .resolve(DECLARING_GROUP, "Total")

        assertIs<Reference.Undeclared>(
            reference,
            "a field A12 drops before checking must not be reachable by short name — the fallback's " +
                "denominator is the resolvable elements, and this is the arm whose loss a surviving mutant " +
                "showed nothing was measuring",
        )
    }

    private companion object {
        const val DECLARING_GROUP = "/Order/Lines"
    }
}
