package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The acceptance rule of [AnchorBudget], on scripted observations rather than a clock — the sibling of
 * [GrowthFloorRetryTest] and [PairedAcceptanceTest].
 *
 * The two properties worth attacking are both here: a **uniform** slowdown must fail (that is the whole reason
 * this bar exists, since no growth ratio can see one), and an **unanchored** observation must not quietly pass in
 * the lane that is supposed to carry the bar.
 */
class PerfAnchorBudgetTest {

    private val key = ObservationKey(
        lane = "B1 sub-quadratic check",
        identityLabel = "diagnostic/B1-per-row@1 fingerprint=475bcd",
        n = 5_000,
        engine = "interpreter",
        clock = "cpu",
    )

    private val tolerance = AnchorTolerance(factor = 1.5, slackMs = 1.0, staleFraction = 0.6)

    private fun budget(unanchoredIsAFailure: Boolean = false, anchorMs: Double? = 8.30) = AnchorBudget(
        anchored = anchorMs?.let { mapOf(key.text to it) } ?: emptyMap(),
        unanchoredIsAFailure = unanchoredIsAFailure,
        tolerance = tolerance,
    )

    @Test
    fun anUnchangedMeasurementCostsExactlyOneObservation() {
        var remeasured = 0
        budget().assertWithinBudget(key, 8.40, TimingEnforcement.GATED) { remeasured++; 8.40 }
        assertEquals(0, remeasured, "a passing budget must not pay for re-measurements it does not need")
    }

    @Test
    fun aMeasurementInsideTheTolerancePasses() {
        budget().assertWithinBudget(key, 13.44, TimingEnforcement.GATED) { 13.44 }   // 8.30 × 1.5 + 1.0 = 13.45
    }

    /** The class of regression NO growth ratio can see: every scale slower by the same factor. */
    @Test
    fun aUniformSlowdownFails() {
        val failure = assertThrows(IllegalStateException::class.java) {
            budget().assertWithinBudget(key, 16.60, TimingEnforcement.GATED) { 16.60 }   // exactly 2× the anchor
        }
        assertTrue("16.60ms" in failure.message.orEmpty() && "13.45ms" in failure.message.orEmpty()) {
            "the failure must state what was measured and what the ceiling was: ${failure.message}"
        }
        assertTrue("8.30ms" in failure.message.orEmpty() && "1.5× + 1.0ms" in failure.message.orEmpty()) {
            "…and the anchor plus the tolerance it was measured against: ${failure.message}"
        }
    }

    /** The absolute term, without which a sub-2 ms lane would trip on a fraction of a millisecond. */
    @Test
    fun aTinyLaneIsNotJudgedOnAFractionOfAMillisecond() {
        val tiny = ObservationKey(
            lane = "wide sibling-groups sub-quadratic",
            identityLabel = "diagnostic/wideGroups-50@1 fingerprint=600f8f",
            n = 50,
            engine = "interpreter",
            clock = "cpu",
        )
        val budget = AnchorBudget(mapOf(tiny.text to 1.13), unanchoredIsAFailure = true, tolerance = tolerance)
        budget.assertWithinBudget(tiny, 2.60, TimingEnforcement.GATED) { 2.60 }      // inside 1.13 × 1.5 + 1.0 = 2.70
        assertThrows(IllegalStateException::class.java) {
            budget.assertWithinBudget(tiny, 3.40, TimingEnforcement.GATED) { 3.40 }  // a genuine 3× on a tiny lane still fails
        }
    }

    @Test
    fun oneContendedSliceIsAbsorbedByTheMedianOfReMeasurements() {
        var remeasured = 0
        budget().assertWithinBudget(key, 25.0, TimingEnforcement.GATED) { remeasured++; 8.5 }
        assertEquals(2, remeasured, "a breach must be re-measured to the full attempt count")
    }

    @Test
    fun oneLowOutlierCannotClearARealSlowdown() {
        val attempts = listOf(20.0, 8.0).iterator()
        val failure = assertThrows(IllegalStateException::class.java) {
            budget().assertWithinBudget(key, 20.0, TimingEnforcement.GATED) { attempts.next() }
        }
        assertTrue("median" in failure.message.orEmpty()) {
            "acceptance must be the median of the attempts, which is what makes the retry sound: ${failure.message}"
        }
    }

    /** A runner class nobody has recorded yet: nothing to compare, so nothing to fail — even where required. */
    @Test
    fun aRunnerClassWithNoBlockAtAllIsBootstrapAndNeverFails() {
        for (required in listOf(false, true)) {
            AnchorBudget(anchored = null, unanchoredIsAFailure = required, tolerance = tolerance)
                .assertWithinBudget(key, 8.40, TimingEnforcement.GATED) { 8.40 }
        }
    }

    @Test
    fun anUnanchoredKeyInsideAnAnchoredClassIsReportedAndPassesLocally() {
        budget(anchorMs = null).assertWithinBudget(key, 8.40, TimingEnforcement.GATED) { 8.40 }
    }

    /**
     * The dangerous case: the class is anchored, so a missing key means a bar went missing, not a new machine.
     *
     * The exception TYPE is load-bearing, not incidental. `TimingEnforcement` downgrades a timing breach to a
     * report on shared hardware, and both failures used to be a plain `IllegalStateException` from a `check` — so
     * the hosted flag swallowed this one too, putting "a bar silently disappeared" back on the one runner nobody
     * watches interactively. Asserting the distinct type is what keeps the two apart.
     */
    @Test
    fun anUnanchoredKeyInsideAnAnchoredClassFailsWhereTheBarIsRequired() {
        val failure = assertThrows(UnanchoredObservationException::class.java) {
            budget(unanchoredIsAFailure = true, anchorMs = null).assertWithinBudget(key, 8.40, TimingEnforcement.GATED) { 8.40 }
        }
        assertTrue(PerfAnchors.REQUIRED_PROPERTY in failure.message.orEmpty()) {
            "the refusal must name the property that demanded it: ${failure.message}"
        }
        assertTrue("UNANCHORED" in failure.message.orEmpty() && key.text in failure.message.orEmpty()) {
            "…and the key that needs refreshing: ${failure.message}"
        }
    }

    /** A refreshed scenario changes its fingerprint, so its anchor must not silently carry over to new work. */
    @Test
    fun aChangedScenarioFingerprintDropsItsAnchor() {
        val refreshed = key.copy(identityLabel = "diagnostic/B1-per-row@1 fingerprint=0000ff")
        val failure = assertThrows(UnanchoredObservationException::class.java) {
            budget(unanchoredIsAFailure = true).assertWithinBudget(refreshed, 8.40, TimingEnforcement.GATED) { 8.40 }
        }
        assertTrue("UNANCHORED" in failure.message.orEmpty()) { failure.message.orEmpty() }
    }

    /**
     * The other half of the type split: a TOLERANCE breach must NOT be the structural type, or the enforcement
     * branch would refuse to downgrade a genuine timing failure on shared hardware and the nightly would red on
     * exactly the noise the split exists to absorb.
     */
    @Test
    fun aToleranceBreachIsNotTheStructuralRefusal() {
        val failure = assertThrows(IllegalStateException::class.java) {
            budget(unanchoredIsAFailure = true).assertWithinBudget(key, 99.0, TimingEnforcement.GATED) { 99.0 }
        }
        assertTrue(failure !is UnanchoredObservationException) {
            "a slow measurement is a timing failure, which TimingEnforcement governs; only a MISSING key is " +
                "structural: ${failure.message}"
        }
    }

    /** One scenario at one scale is measured under two regimes; they must never share a number. */
    @Test
    fun theWarmAndColdLanesOfOneScenarioHaveDistinctKeys() {
        val cold = key.copy(lane = "COLD first-call (startup)", clock = "wall")
        assertTrue(cold.text != key.text)
        assertThrows(IllegalStateException::class.java) {
            budget(unanchoredIsAFailure = true).assertWithinBudget(cold, 8.40, TimingEnforcement.GATED) { 8.40 }
        }
    }

    @Test
    fun aToleranceThatWouldFailAnUnchangedMeasurementIsRejected() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AnchorTolerance(factor = 1.0, slackMs = 0.0, staleFraction = 0.6)
        }
        assertTrue("unchanged" in failure.message.orEmpty()) { failure.message.orEmpty() }
        assertThrows(IllegalArgumentException::class.java) {
            AnchorTolerance(factor = 1.5, slackMs = -1.0, staleFraction = 0.6)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnchorBudget(anchored = emptyMap(), unanchoredIsAFailure = false, tolerance = tolerance, attempts = 2)
        }
    }

    // ── the hosted flag's two tiers (IG137 piece a) ───────────────────────────────────────────────────

    /**
     * **A breach inside the hosted noise envelope must say so.** The `linux-amd64-ci` class has been measured
     * producing 2.29x run-to-run spreads on absolute medians for ONE unchanged commit, so a 1.8x breach there is
     * something identical code has actually done — reporting it in the same words as a 4x breach is what makes a
     * flag stream unreadable, and an unreadable flag is indistinguishable from no flag.
     */
    @Test
    fun aHostedBreachInsideTheMeasuredSpreadIsMarkedNotAttributable() {
        val flag = PerfAnchors.hostedFlagFor(breach(accepted = 18.0, anchor = 10.0))

        assertTrue(flag.contains("within the same-commit spread")) { flag }
        assertTrue(flag.contains("not attributable")) { flag }
    }

    /** …and one BEYOND it earns the re-measure instruction, which is the whole point of splitting the tiers. */
    @Test
    fun aHostedBreachBeyondTheMeasuredSpreadIsMarkedACandidate() {
        val flag = PerfAnchors.hostedFlagFor(breach(accepted = 40.0, anchor = 10.0))

        assertTrue(flag.contains("BEYOND the same-commit spread")) { flag }
        assertTrue(flag.contains("re-measure this lane in isolation")) { flag }
    }

    /**
     * THE BOUNDARY, asserted from both sides on the same anchor. Without it a classifier that always said
     * "within" would satisfy the first case and one that always said "BEYOND" the second.
     */
    @Test
    fun theTierBoundaryIsTheWorstMeasuredSameCommitSpread() {
        val below = PerfAnchors.hostedFlagFor(
            breach(accepted = 10.0 * (PerfAnchors.HOSTED_SAME_COMMIT_SPREAD - 0.01), anchor = 10.0),
        )
        val at = PerfAnchors.hostedFlagFor(
            breach(accepted = 10.0 * PerfAnchors.HOSTED_SAME_COMMIT_SPREAD, anchor = 10.0),
        )

        assertTrue(below.contains("within the same-commit spread")) { below }
        assertTrue(at.contains("BEYOND the same-commit spread")) { at }
    }

    /**
     * A failure that is NOT an anchor breach still gets reported, unclassified. The hosted path catches every
     * throwable from the budget, and a tier claim about something that carries no anchor multiple would be
     * fabricated.
     */
    @Test
    fun aNonBreachFailureIsReportedWithoutATierClaim() {
        val flag = PerfAnchors.hostedFlagFor(IllegalStateException("something else went wrong"))

        assertTrue(flag.contains("something else went wrong")) { flag }
        assertTrue(!flag.contains("same-commit spread")) { "no multiple exists to classify: $flag" }
    }

    private fun breach(accepted: Double, anchor: Double) =
        AnchorBreachException("scripted breach", accepted = accepted, anchor = anchor)
}
