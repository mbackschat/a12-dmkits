package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That A12's own guidance actually reaches a finding, on a real report rather than on the table alone.
 *
 * The table is generated and guarded on the Java side (`CheckerCorrectiveProjectionGuardTest` re-derives it
 * from `StructuralKernelFix` and `RkCode`). What no guard over there can see is the last step — whether a
 * finding this checker produces finds its entry — which is the "parsed but never consumed" class one layer
 * down: a table can be perfectly projected and reach nobody.
 */
class CorrectiveTest {

    /** A model whose one rule names an entity that does not resolve — `MVK_INVALID_ENTITY`, which is served. */
    private val unresolvable = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"Check","Rule":{
              "errorCondition":"FieldFilled(NoSuchField)","errorEntityRelPath":"../Price",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    @Test
    fun aFindingWhoseCodeItsOwnerExplainsCarriesTheGuidance() {
        val finding = check(unresolvable).findings.single { it.code == "MVK_INVALID_ENTITY" }
        val corrective = assertNotNull(finding.corrective, "MVK_INVALID_ENTITY is a served code")
        assertTrue(corrective.fix.isNotBlank(), corrective.fix)
        assertTrue(corrective.meaning.isNotBlank(), corrective.meaning)
        // The two texts answer different questions, which is why they are separate fields rather than one.
        assertTrue(corrective.fix != corrective.meaning, "the fix and the meaning must not be one text")
    }

    /**
     * A code no owner explains carries nothing — absence is the ordinary case, not a gap.
     *
     * [CK_MODEL_NOT_BUILDABLE] is this checker's own code rather than A12's, so no A12 owner could have
     * guidance for it. Asserting the null keeps the lookup honest: a table that answered for everything
     * would be inventing guidance.
     */
    @Test
    fun aCodeNoOwnerExplainsCarriesNothing() {
        val finding = Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "anything",
        )
        assertNull(finding.corrective)
    }

    /** Every projected entry is reachable by the code of some finding this checker can emit. */
    @Test
    fun theProjectionCarriesOnlyCodesTheCheckerCanEmit() {
        assertEquals(
            emptyList(),
            KERNEL_CORRECTIVES.keys.filterNot { it.startsWith("MVK_") || it.startsWith("RK_") },
            "the projection may carry only A12's own code vocabularies",
        )
    }
}
