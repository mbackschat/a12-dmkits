package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The unsound-DSL gate's two arms must answer identically while the estate is part-migrated.
 *
 * **What the shared core guarantees and what it does not.** Both arms call one `unsoundDslIn`, so the
 * DECISION cannot diverge — that much is structural. What no structure guarantees is that they feed it
 * equivalent INPUTS: the untracked arm passes the fact base's declarations, the tracked one passes the
 * cursors, and if either ever narrowed its list the arms would disagree silently. The consequence is severe
 * and invisible: this gate decides whether a whole class of message and error-text families says anything at
 * all, so a divergence makes some of them go quiet and others loud on the same model, with every family's own
 * test still green.
 */
class DeclarationGateArmsTest {

    /** A rule whose condition names nothing declared, and a computation whose brackets do not balance. */
    private val unsoundModel = ModelSource(
        """
        {"header":{"id":"orders","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"Dangling","Rule":{
              "errorEntityRelPath":"../Code","errorCondition":"FieldFilled(Nowhere)"}},
            {"type":"Computation","id":"c0","name":"Broken","Computation":{
              "computationAlternatives":[{"operation":"(1 + 2"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    @Test
    fun bothArmsOfTheUnsoundDslGateNameTheSameDeclarations() {
        val admitted = admit(unsoundModel)
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        val prepared = prepare(admitted)

        // The untracked arm on purpose: a blanket rewrite of test call sites made both sides tracked on
        // 2026-09-17, and the test kept passing while comparing one arm with itself.
        val untracked = prepared.declarationsWithUnsoundDsl()
        val tracked = prepared.tracked().declarationsWithUnsoundDsl()

        // The positive control: an empty answer from both arms would satisfy the equality below while proving
        // nothing, and this gate returning nothing is exactly the failure that makes families go loud.
        assertTrue(
            untracked.isNotEmpty(),
            "the fixture must really carry unsound DSL, or the equality is between two empty sets",
        )
        assertEquals(untracked, tracked, "the gate's two arms must name the same declarations")
    }
}
