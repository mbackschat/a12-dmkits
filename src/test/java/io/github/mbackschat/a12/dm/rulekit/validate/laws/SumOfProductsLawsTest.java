package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;

/**
 * KERNEL-FINDINGS §9 — the measured operand-pair boundary of {@code SumOfProducts}. Two facts the earlier
 * "same group, else {@code MVK_DIFFERENT_GROUPS}" reading got wrong, both locked below:
 *
 * <ul>
 * <li>the group that must match is the fields' <b>owning</b> group, not the starred one — two lists can share
 *     {@code Lines*} and still be rejected when one field sits a level deeper; and</li>
 * <li>the rejection code is <b>pair-dependent</b>: an operand that sits under a repeatable level it does not
 *     wildcard is {@code MVK_NO_WILDCARD}, while one outside the other's starred group is
 *     {@code MVK_DIFFERENT_GROUPS}. Only a pair with no repeatable level at all is
 *     {@code MVK_REPEATABLE_LEVEL_REQUIRED}.</li>
 * </ul>
 *
 * <p>This is the measured basis for rulekit's authoring refusal and for the code each refusal quotes; the
 * source counterpart is {@code CheckEntityListenUtils.checkAllEntitiesInSameGroup}.
 */
class SumOfProductsLawsTest {

    private static final String BASKET = ModelDrafts.basket().toJson();

    @Test
    void twoParallelListsOverOneOwningGroupAreAccepted() {
        KernelLaws.acceptsModel(BASKET, "/Basket", "../Total",
                "FieldFilled(Total) And SumOfProducts(Lines*/Qty, Lines*/Qty) > 0");
    }

    @Test
    void theOwningGroupMayItselfBeNonRepeatable() {
        KernelLaws.acceptsModel(BASKET, "/Basket", "../Total",
                "FieldFilled(Total) And "
                        + "SumOfProducts(Lines*/Packaging/Weight, Lines*/Packaging/Weight) > 0");
    }

    @Test
    void twoFieldsFromDifferentRepeatableGroupsAreRejected() {
        KernelLaws.rejectsLogistics("/Shipment", "../ShipmentRef",
                "FieldFilled(ShipmentRef) And SumOfProducts(Lines*/OrderedQty, Picks*/PickedQty) > 100000",
                "MVK_DIFFERENT_GROUPS");
    }

    @Test
    void twoListsSharingAStarredGroupButNotAnOwningGroupAreRejected() {
        KernelLaws.rejectsModel(BASKET, "/Basket", "../Total",
                "FieldFilled(Total) And SumOfProducts(Lines*/Qty, Lines*/Packaging/Weight) > 0",
                "MVK_DIFFERENT_GROUPS");
    }

    @Test
    void aPlainFieldOutsideTheStarredGroupIsADifferentGroup() {
        KernelLaws.rejectsModel(BASKET, "/Basket", "../Total",
                "FieldFilled(Total) And SumOfProducts(Lines*/Qty, Fees/Handling) > 0",
                "MVK_DIFFERENT_GROUPS");
    }

    @Test
    void aPlainFieldInsideTheStarredGroupLacksTheWildcard() {
        KernelLaws.rejectsModel(BASKET, "/Basket", "../Total",
                "FieldFilled(Total) And SumOfProducts(Lines*/Qty, Lines/Packaging/Weight) > 0",
                "MVK_NO_WILDCARD");
    }

    @Test
    void aDescendantListLeavesTheOuterRepeatableLevelUnwildcarded() {
        KernelLaws.rejectsProject("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And "
                        + "SumOfProducts(Milestones*/Budget, Milestones/Tasks*/Effort) > 0",
                "MVK_NO_WILDCARD");
    }

    @Test
    void aStarAboveTheLowestRepeatableLevelIsRejected() {
        KernelLaws.rejectsProject("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And "
                        + "SumOfProducts(Milestones*/Tasks/Effort, Milestones*/Tasks/Effort) > 0",
                "MVK_NO_WILDCARD");
    }

    @Test
    void aMultiStarOperandIsRejected() {
        KernelLaws.rejectsProject("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And "
                        + "SumOfProducts(Milestones*/Tasks*/Effort, Milestones*/Tasks*/Effort) > 0",
                "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED");
    }

    @Test
    void plainFieldsAreRejectedBecauseNoRepeatableLevelExists() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And SumOfProducts(Quantity, BackorderQuantity) > 0",
                "MVK_REPEATABLE_LEVEL_REQUIRED");
    }

    @Test
    void aFilteredOperandIsRejectedOnEitherSide() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And "
                        + "SumOfProducts(Items*/Count Having [Items/Sku] == \"X\", Items*/UnitWeight) > 0",
                "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And "
                        + "SumOfProducts(Items*/Count, Items*/UnitWeight Having [Items/Sku] == \"X\") > 0",
                "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED");
    }
}
