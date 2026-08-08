package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §9 — {@code SumOfProducts} requires its two repeatable number fields to live in the
 * <b>same group</b> (source {@code CheckEntityListenUtils.checkAllEntitiesInSameGroup} →
 * {@code MVK_DIFFERENT_GROUPS}), and only the lowest repeatable level may be starred. The latter is the
 * measured basis for rulekit's authoring refusal.
 */
class SumOfProductsLawsTest {

    @Test
    void twoNumbersInTheSameRepeatableGroupAreAccepted() {
        // order's Items group holds Count + UnitWeight (both number, same group).
        KernelLaws.acceptsOrder("/Order", "../UnitPrice",
                "FieldFilled(UnitPrice) And [UnitPrice] != SumOfProducts(Items*/Count, Items*/UnitWeight)");
    }

    @Test
    void fieldsFromDifferentGroupsAreRejected() {
        // logistics has two separate repeatable groups Lines + Picks.
        KernelLaws.rejectsModel(ModelDrafts.logistics().toJson(), "/Shipment", "../ShipmentRef",
                "FieldFilled(ShipmentRef) And SumOfProducts(Lines*/OrderedQty, Picks*/PickedQty) > 100000",
                "MVK_DIFFERENT_GROUPS");
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
    void aFilteredOperandIsRejected() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And "
                        + "SumOfProducts(Items*/Count Having [Items/Sku] == \"X\", Items*/UnitWeight) > 0",
                "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED");
    }
}
