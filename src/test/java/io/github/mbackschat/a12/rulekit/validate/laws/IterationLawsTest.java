package io.github.mbackschat.a12.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/** KERNEL-FINDINGS §9 — wildcard slot laws + the RepetitionNotUnique position law. */
class IterationLawsTest {

    @Test
    void aStarInAScalarSlotIsRejected() {
        KernelLaws.rejects("/Subscription", "../Tier",
                "FieldFilled(Tier) And [Addons*/MonthlyFee] > 100", "MVK_NO_WILDCARDS_ALLOWED");
    }

    @Test
    void aStarOnANonRepeatableGroupIsRejected() {
        KernelLaws.rejects("/Subscription", "../Tier",
                "FieldFilled(Tier) And AtLeastOneFieldFilled(Billing*/BaseFee)", "MVK_INVALID_WILDCARD");
    }

    @Test
    void aStarInAListSlotIsAccepted() {
        KernelLaws.accepts("/Subscription", "../Tier",
                "FieldFilled(Tier) And AtLeastOneFieldFilled(Addons*/Name)");
    }

    @Test
    void currentRepetitionRequiresTheRuleToIterateOverTheGroup() {
        // iterating (error field under Addons) → accepted
        KernelLaws.accepts("/Subscription/Addons", "../MonthlyFee",
                "FieldFilled(MonthlyFee) And CurrentRepetition(/Subscription/Addons) > 1");
        // non-iterating → MVK_NO_WILDCARD (position-dependent, kernel-delegated)
        KernelLaws.rejects("/Subscription", "../Tier",
                "FieldFilled(Tier) And CurrentRepetition(/Subscription/Addons) > 1", "MVK_NO_WILDCARD");
    }

    @Test
    void repetitionNotUniqueKeysMustBeRepeatableRelativeToTheRuleGroup() {
        // from INSIDE the repeatable group → rejected (position-dependent, kernel-delegated)
        KernelLaws.rejects("/Subscription/Addons", "../Name",
                "RepetitionNotUnique(/Subscription/Addons/Name)", "MVK_REPEATABLE_GROUP_MISSING");
        // from the PARENT level → the canonical shape validates
        KernelLaws.accepts("/Subscription", "../Addons/Name", "RepetitionNotUnique(Addons/Name)");
    }
}
