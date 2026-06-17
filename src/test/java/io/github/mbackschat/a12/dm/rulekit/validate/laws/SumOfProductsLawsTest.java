package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §9 — {@code SumOfProducts} requires its two repeatable number fields to live in the
 * <b>same group</b> (source {@code CheckEntityListenUtils.checkAllEntitiesInSameGroup} →
 * {@code MVK_DIFFERENT_GROUPS}). Two fields from different repeatable groups are rejected.
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
}
