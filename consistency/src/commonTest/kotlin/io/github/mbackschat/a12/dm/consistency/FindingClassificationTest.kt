package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * B2's remaining separation: which kind of finding may reject a model.
 *
 * [proposal §5.4](../../../../../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md) admits exactly three kinds and
 * gives only one of them that power. Every case below holds severity at ERROR where it can, so the category
 * is the single variable — a policy restriction stated as forcefully as an A12 rejection still is not one.
 */
class FindingClassificationTest {

    private fun finding(category: FindingCategory, severity: FindingSeverity = FindingSeverity.ERROR) =
        Finding(category, severity, code = "X", explain = "x", at = "/Order/Code")

    /** The positive control: without it every case below would pass on a checker that rejects nothing. */
    @Test
    fun aMeasuredA12ConstraintViolationRejectsTheModel() {
        assertEquals(
            ConsistencyStatus.Invalid,
            statusOf(listOf(finding(FindingCategory.A12_CONSTRAINT))),
        )
    }

    @Test
    fun anAuthoringPolicyRestrictionDoesNotRejectTheModel() {
        assertEquals(
            ConsistencyStatus.Inconclusive,
            statusOf(listOf(finding(FindingCategory.AUTHORING_POLICY))),
        )
    }

    @Test
    fun anEvaluatorSupportGapDoesNotRejectAStaticallyCheckableDeclaration() {
        assertEquals(
            ConsistencyStatus.Inconclusive,
            statusOf(listOf(finding(FindingCategory.EVALUATOR_SUPPORT))),
        )
    }

    /**
     * Severity is load-bearing too, which is what keeps the category rule from being the whole story: the
     * Kernel's own consistency check rejects on ERROR notifications alone, so an A12 warning is a finding
     * a consumer must see and not a rejection.
     */
    @Test
    fun anA12ConstraintWarningDoesNotRejectTheModel() {
        assertEquals(
            ConsistencyStatus.Inconclusive,
            statusOf(listOf(finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.WARNING))),
        )
    }

    @Test
    fun aRejectionStandsBesideFindingsThatCannotCauseOne() {
        assertEquals(
            ConsistencyStatus.Invalid,
            statusOf(
                listOf(
                    finding(FindingCategory.AUTHORING_POLICY),
                    finding(FindingCategory.A12_CONSTRAINT),
                    finding(FindingCategory.EVALUATOR_SUPPORT),
                ),
            ),
        )
    }

    /**
     * No result may yet claim complete coverage, and none may be VALID — no required check family is
     * implemented. The exhaustive `when` is the enforcement locus: adding a `Valid` variant stops this
     * compiling, so the bar cannot be lifted silently rather than with its coverage evidence.
     */
    @Test
    fun noStatusCanYetClaimCompleteCoverage() {
        listOf(ConsistencyStatus.Invalid, ConsistencyStatus.Inconclusive).forEach { status ->
            val complete = when (status) {
                ConsistencyStatus.Invalid -> status.complete
                ConsistencyStatus.Inconclusive -> status.complete
            }
            assertFalse(complete, "$status must not assert completed coverage")
        }
    }
}
