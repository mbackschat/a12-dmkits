package io.github.mbackschat.a12.dm.rulekit.validate.laws;

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
    void parallelIterationOverIndexKeyedGroupsIsSupportedNotBlocked() {
        // Two repeatable groups sharing an index field (Lines, Picks on LineRef) iterate JOINTLY;
        // rulekit's existing handles express this with no special construct (verified 2026-06-04).
        KernelLaws.acceptsLogistics("/Shipment/Lines", "../OrderedQty",
                "FieldsNotCollectivelyFilled(/Shipment/Lines/OrderedQty, /Shipment/Picks/PickedQty)");
        // and a plain cross-group reference is LEGAL under parallel iteration — which is exactly why
        // the MVK_NO_WILDCARD missing-asterisk pre-check stays kernel-delegated (a naive check would
        // wrongly reject this). KERNEL-FINDINGS §9.
        KernelLaws.acceptsLogistics("/Shipment/Lines", "../OrderedQty",
                "FieldFilled(OrderedQty) And FieldFilled(/Shipment/Picks/PickedQty)");
    }

    @Test
    void repetitionNotUniqueKeysMustBeRepeatableRelativeToTheRuleGroup() {
        // from INSIDE the repeatable group → rejected (position-dependent, kernel-delegated)
        KernelLaws.rejects("/Subscription/Addons", "../Name",
                "RepetitionNotUnique(/Subscription/Addons/Name)", "MVK_REPEATABLE_GROUP_MISSING");
        // from the PARENT level → the canonical shape validates
        KernelLaws.accepts("/Subscription", "../Addons/Name", "RepetitionNotUnique(Addons/Name)");
    }

    @Test
    void repetitionNotUniqueKeysMustLieOnOnePath() {
        // keys in two parallel repeatable groups (Lines, Picks — neither below the other) → rejected.
        // Source: CheckEntityListenUtils.checkAllRepeatableGroupsOnSamePath (no longest-shared-path → throw).
        KernelLaws.rejectsLogistics("/Shipment/Lines", "../LineRef",
                "RepetitionNotUnique(/Shipment/Lines/LineRef, /Shipment/Picks/LineRef)", "MVK_NOT_IN_SAME_PATH");
    }

    @Test
    void repetitionNotUniqueFromScopeMustContainTheKeys() {
        // @From Picks is repeatable, but the key lives in Lines (not under Picks) → MVK_NOT_IN_GROUP.
        // Source: CheckEntityListenUtils.checkAllEntitiesInGroup (each key's parent path must start with the @From group path).
        KernelLaws.rejectsLogistics("/Shipment/Lines", "../LineRef",
                "RepetitionNotUnique(/Shipment/Lines/LineRef @From /Shipment/Picks)", "MVK_NOT_IN_GROUP");
    }

    @Test
    void errorFieldMustShareTheIterationScopeOfTheReferencedRepeatableFields() {
        // KERNEL-FINDINGS §9 — a rule's iteration scope is determined by the repeatable fields it
        // REFERENCES (incl. its error field), NOT by where the rule node physically sits. The error
        // field must share that scope. This is the law behind the field-move behavior (and why a
        // mechanical "co-move" is a non-goal — it can't reconcile a genuine cross-cardinality split):
        //
        // (a) error field in a SHALLOWER scope than a per-row operand → rejected. The rule references
        //     Addons/MonthlyFee (per-row, so it iterates over Addons), but its error field is the
        //     non-repeatable sibling Billing/BaseFee — a different scope.
        KernelLaws.rejects("/Subscription/Addons", "../../Billing/BaseFee",
                "FieldFilled(MonthlyFee) And [MonthlyFee] > 100", "MVK_ERROR_FIELD_NOT_IN_RULEGROUP");
        // (b) a rule may reach DOWN into a repeatable for its error field — the error field then drives
        //     the iteration, and per-row operands resolve consistently → accepted.
        KernelLaws.accepts("/Subscription", "../Addons/MonthlyFee",
                "FieldFilled(Addons/MonthlyFee) And [Addons/MonthlyFee] > 100");
        // (c) the canonical sibling case — error field and per-row operand in the SAME repeatable scope.
        KernelLaws.accepts("/Subscription/Addons", "../MonthlyFee",
                "FieldFilled(MonthlyFee) And [MonthlyFee] > 100");
    }

    @Test
    void aPureNegativeInParallelIterationIsRejected() {
        // BA L919: in a parallel iteration a negative condition is disallowed UNLESS combined with a
        // positive one. A bare NoFieldFilled over the two parallel-iterated groups is rejected; the
        // positive-bearing FieldsNotCollectivelyFilled form (locked above) is accepted.
        // Source: ParserService → IterationNotValidVisitor.testParallelIteration (IterationType.PARALLEL traversal).
        KernelLaws.rejectsLogistics("/Shipment/Lines", "../OrderedQty",
                "NoFieldFilled(/Shipment/Lines/OrderedQty, /Shipment/Picks/PickedQty)",
                "MVK_PARALLEL_ITERATION_INVALID");
    }
}
