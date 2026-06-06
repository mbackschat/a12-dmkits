package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §10 — path / wildcard laws (accept/reject, parse + consistency tier). Uses the
 * nested-repeatable {@code project} fixture (Milestones ▸ Tasks), the structure needed to exercise the
 * wildcard-propagation rule.
 */
class PathLawsTest {

    @Test
    void anOuterWildcardRequiresEveryRepeatableGroupBelowItToAlsoWildcard() {
        // outer * on Milestones but a plain (non-*) Tasks below it → rejected
        KernelLaws.rejectsProject("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And Sum(Milestones*/Tasks/Effort) > 0", "MVK_NO_WILDCARD");
    }

    @Test
    void wildcardingEveryRepeatableLevelIsAccepted() {
        // both repeatable levels starred → the flattened nested list is valid
        KernelLaws.acceptsProject("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And Sum(Milestones*/Tasks*/Effort) > 0");
    }

    @Test
    void aSingleWildcardOverTheOuterRepeatableAggregatesItsOwnField() {
        // starring just the outer repeatable to aggregate its own (non-nested) field is fine
        KernelLaws.acceptsProject("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And Sum(Milestones*/Budget) > 0");
    }
}
