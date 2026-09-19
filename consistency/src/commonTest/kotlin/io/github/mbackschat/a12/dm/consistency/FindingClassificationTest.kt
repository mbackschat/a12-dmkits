package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B2's remaining separation: which kind of finding may reject a model.
 *
 * [proposal §5.4](../../../../../../../../../../docs/archived/PORTABLE-CONSISTENCY-PROPOSAL.md) admits exactly three kinds and
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
     * No result any finding set can produce may claim complete coverage, and none may be VALID. The loop is
     * over `statusOf`'s actual outputs rather than a hand-listed pair, so a later `Valid` variant that
     * `statusOf` can return fails here instead of merely forcing this test to be edited — which is what an
     * exhaustive `when` over the sealed type would have done, and is not the same guarantee.
     */
    @Test
    fun noStatusAnyFindingSetProducesCanClaimCompleteCoverage() {
        val everyFinding = FindingCategory.entries.flatMap { category ->
            FindingSeverity.entries.map { severity -> finding(category, severity) }
        }

        (everyFinding.map { listOf(it) } + listOf(emptyList(), everyFinding)).forEach { findings ->
            assertFalse(
                statusOf(findings).complete,
                "$findings must not assert completed coverage. If you are here because you BUILT the coverage " +
                    "registry, this is the locus two KDocs point at while saying it does not exist — sweep " +
                    "ConsistencyStatus.complete and CheckOutcome's 'two different things get called the " +
                    "coverage registry' paragraph in the same change, or they ship stale to consumers",
            )
        }
    }

    /**
     * A12's own `Severity` records that `ordinal()` compares two severities, so our mirror's order is a
     * contract rather than a listing. Without this, reordering the constants passes every other case here.
     */
    @Test
    fun severityOrdersFromLeastToMostSevere() {
        assertTrue(FindingSeverity.ERROR > FindingSeverity.WARNING)
        assertTrue(FindingSeverity.WARNING > FindingSeverity.INFO)
    }
}
