package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Half-typed text is reported and does not condemn the draft — on BOTH draft arms.
 *
 * **This is the enforcement locus for a contract that was advertised four times and checked nowhere.**
 * `checkDraft`'s KDoc says in bold that half-typed text "is INCOMPLETE, not invalid" and that "nothing here
 * promotes that to a rejection"; `CheckDraftTest.anIncompleteDraftIsNotBlamedOnTheAuthorAsAnInvalidOne` says
 * it in its own NAME while asserting only the finding's code; and `CONSISTENCY-API.md` published it to
 * consumers. Measured 2026-09-18 through the packed npm package, a draft ending in `And` came back
 * `status: INVALID` with the finding at `justifiesRejection: true` — the over-rejection all four statements
 * forbid, on every keystroke an author spends mid-expression. A name is not a guard.
 *
 * **What is asserted is the whole published triple, not just the status.** A consumer is told to read
 * `justifiesRejection` rather than `severity` to decide rejection, so a fix that moved the status while
 * leaving that flag true would satisfy a status assertion and still mislead every consumer following the
 * documentation.
 *
 * **Both arms, because they are two arms.** The stateless `checkDraft` prepares per call; the snapshot's
 * `draft` answers from a held fact base. They build their input separately, which is exactly how paired arms
 * in this module have diverged before, so the contract is asserted on each rather than on one and assumed for
 * the other.
 */
class DraftIncompletenessVerdictTest {

    @Test
    fun theStatelessDraftReportsIncompletenessWithoutCondemningIt() {
        val report = checkDraft(ModelSource(MODEL), RULE, "errorCondition", ENDS_EARLY)

        assertEquals(
            listOf("MVK_INCOMPLETE_INPUT"),
            report.introduced.map { it.code },
            "the fixture must reach the incompleteness class, or this row locks nothing: ${report.introduced}",
        )
        assertEquals(
            FindingSeverity.WARNING,
            report.introduced.single().severity,
            "reported, at a severity that says unfinished rather than wrong",
        )
        assertEquals(
            FindingCategory.A12_CONSTRAINT,
            report.introduced.single().category,
            "the category stays A12's, because the code IS A12's — only the consequence changes",
        )
        assertTrue(
            report.introduced.none { it.justifiesRejection },
            "a consumer is told to read justifiesRejection, so moving only the status would still mislead it",
        )
        assertEquals(
            ConsistencyStatus.Inconclusive,
            report.status,
            "and the draft's own verdict must not be INVALID while the author is still typing",
        )
    }

    @Test
    fun theSnapshotDraftReadsItTheSameWay() {
        val held = snapshotOf(listOf(ModelSource(MODEL)), MODEL_ID)

        val stateless = checkDraft(ModelSource(MODEL), RULE, "errorCondition", ENDS_EARLY)
        val fromHandle = held.draft(RULE, "errorCondition", ENDS_EARLY).edit

        assertEquals(
            stateless.status,
            fromHandle.status,
            "the two arms must reach the same verdict about the same draft",
        )
        assertEquals(
            stateless.introduced,
            fromHandle.introduced,
            "and attribute it identically, severity included",
        )
    }

    /**
     * A SAVED edit carrying the same text stays invalid — the mirror the demotion must not cross.
     *
     * The draft exception is about text that is not stored yet. A12 does reject a stored condition that ends
     * early, so an edit reporting otherwise would be an over-acceptance: the author would be told their save
     * was fine. This row is what keeps the demotion scoped to drafts rather than applied to the shared
     * attribution both calls use.
     */
    @Test
    fun aSavedEditWithTheSameTextIsStillInvalid() {
        val edited = MODEL.replace(LIVE_CONDITION, ENDS_EARLY)
        val report = checkEdit(ModelSource(MODEL), ModelSource(edited))

        assertTrue(
            report.introduced.any { it.code == "MVK_INCOMPLETE_INPUT" && it.justifiesRejection },
            "a SAVED incomplete condition is a rejection; the draft exception must not reach checkEdit: " +
                "${report.introduced}",
        )
        assertEquals(ConsistencyStatus.Invalid, report.status, "so the edit's verdict is invalid")
    }

    private companion object {
        const val MODEL_ID = "drafts"
        const val RULE = "/Policy/HolderRequired"
        const val LIVE_CONDITION = "FieldFilled(Holder)"

        /** Text that simply ran out — A12's own incompleteness class, not an unbalanced parenthesis. */
        const val ENDS_EARLY = "FieldFilled(Holder) And"

        private val MODEL = """
            {"header":{"id":"$MODEL_ID","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r0","name":"HolderRequired","Rule":{
                  "errorEntityRelPath":"../Holder","errorCondition":"$LIVE_CONDITION",
                  "errorCode":"holder.required",
                  "errorMessage":[{"locale":"en_US","text":"A policy holder is required."}]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
