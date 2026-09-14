package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 *     {@code MVK_DIFFERENT_GROUPS}. Only a pair with no repeatable level at all <b>and a shared owning
 *     group</b> is {@code MVK_REPEATABLE_LEVEL_REQUIRED} — <i>corrected 2026-09-14</i>: this clause read
 *     "no repeatable level at all", which is short by a condition, because the only row that had been run
 *     gave its two operands the same owning group. The precedence grid below measures the pair that
 *     separates them, and {@code MVK_DIFFERENT_GROUPS} wins.</li>
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

    /**
     * WHICH class an operand pair draws when it breaks two of the rules at once.
     *
     * <p>The single-assertion tests above each establish that one shape is refused; none of them establishes
     * the ORDER, and a checker reproducing this family turns on exactly that — reporting the wrong one of two
     * live classes names a fix the author does not need. Two orderings are already implicit above and are
     * kept here as controls; the pair with no repeatable level AND different owning groups is the cell that
     * has never been put to the kernel, and the two rules disagree about it.
     *
     * <p>One model throughout, so no cell moves a variable the cell beside it does not.
     */
    @Test
    void whichClassWinsWhenAnOperandPairBreaksTwoRulesAtOnce() {
        var actual = new java.util.LinkedHashMap<String, String>();
        actual.put("same owning group, both starred — the control", verdict("Lines*/Qty, Lines*/Qty"));
        actual.put("different owning groups, both starred", verdict("Lines*/Qty, Vouchers*/Amount"));
        actual.put("different owning groups by DEPTH, both starred",
                verdict("Lines*/Qty, Lines*/Packaging/Weight"));
        actual.put("a missing star AND different owning groups",
                verdict("Lines*/Qty, Lines/Packaging/Weight"));
        actual.put("one operand repeatable, one not", verdict("Lines*/Qty, Fees/Handling"));
        actual.put("the same UNSTARRED field twice", verdict("Total, Total"));
        actual.put("no repeatable level, DIFFERENT owning groups", verdict("Total, Fees/Handling"));

        var expected = new java.util.LinkedHashMap<String, String>();
        expected.put("same owning group, both starred — the control", KernelLaws.ADMITTED);
        expected.put("different owning groups, both starred", "MVK_DIFFERENT_GROUPS");
        expected.put("different owning groups by DEPTH, both starred", "MVK_DIFFERENT_GROUPS");
        // The wildcard class outranks the owning-group one: this pair breaks both.
        expected.put("a missing star AND different owning groups", "MVK_NO_WILDCARD");
        expected.put("one operand repeatable, one not", "MVK_DIFFERENT_GROUPS");
        // The duplicate gate is STAR-SENSITIVE here as it is on a group list: the starred pair in the
        // control row above is ADMITTED, and the same two spellings unstarred are a duplicate.
        expected.put("the same UNSTARRED field twice", "MVK_DUPLICATE_PARAM1");
        // MEASURED, and it CORRECTS this file's own javadoc: "only a pair with no repeatable level at all
        // is MVK_REPEATABLE_LEVEL_REQUIRED" is short by a condition — the owning groups must also MATCH.
        // `plainFieldsAreRejectedBecauseNoRepeatableLevelExists` could not see that: its two operands share
        // an owning group, so "no repeatable level" and "no repeatable level AND same group" are one thing
        // on the only row that had ever been run.
        expected.put("no repeatable level, DIFFERENT owning groups", "MVK_DIFFERENT_GROUPS");
        assertEquals(expected, actual, "SumOfProducts operand-pair class precedence");
    }

    /**
     * The rest of the gate chain, so a checker reproducing this operator knows the whole order.
     *
     * <p>The grid above measures the classes; these are the cells around them — the arity gate, the kind
     * gate, and the two cells where a FILTER competes with another live defect. The filter matters more than
     * it looks: {@code isWildcardRestricted} reads a filter as restricting EVERY level, so a filtered operand
     * is the only way to reach {@code MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED} at all — the reference reader
     * answers a plain missing star with {@code MVK_NO_WILDCARD} long before this method runs.
     */
    @Test
    void theRestOfTheOperandGateChain() {
        var actual = new java.util.LinkedHashMap<String, String>();
        actual.put("two parallel lists — the control", verdict("Lines*/Qty, Lines*/Qty"));
        actual.put("one operand only", verdict("Lines*/Qty"));
        actual.put("three operands", verdict("Lines*/Qty, Lines*/Qty, Lines*/Qty"));
        actual.put("a GROUP operand", verdict("Lines*, Lines*/Qty"));
        actual.put("a filtered operand whose owning group also differs",
                verdict("Lines*/Qty Having [Lines/Qty] > 0, Fees/Handling"));
        actual.put("a filtered operand that also stars two levels", nestedVerdict(
                "Milestones*/Tasks*/Effort Having [Milestones/Tasks/TaskId] == \"x\", "
                        + "Milestones*/Tasks*/Effort"));
        actual.put("the same two levels starred, unfiltered — the control",
                nestedVerdict("Milestones*/Tasks*/Effort, Milestones*/Tasks*/Effort"));

        var expected = new java.util.LinkedHashMap<String, String>();
        expected.put("two parallel lists — the control", KernelLaws.ADMITTED);
        // `MVK_PARAMSIZE_INVALID_2`, not the underscore-less `MVK_PARAMSIZE_INVALID2` its sibling gates use
        // — two distinct members of the same enum, and this operator's `checkParamSizeIs2` draws the second.
        expected.put("one operand only", "MVK_PARAMSIZE_INVALID_2");
        expected.put("three operands", "MVK_PARAMSIZE_INVALID_2");
        // The DUPLICATE gate answers ahead of the kind gate: a field named beside the group that contains it
        // is an indirect duplicate, so this pair never reaches `checkOnlyFields`.
        expected.put("a GROUP operand", "MVK_DUPLICATE_PARAM2");
        // The same-group gate runs BEFORE the wildcard one, so the filter never gets asked here.
        expected.put("a filtered operand whose owning group also differs", "MVK_DIFFERENT_GROUPS");
        // …and where the owning groups agree the filter wins over the multi-star, because the restriction is
        // read at the LOWEST level, which the loop reaches first.
        expected.put("a filtered operand that also stars two levels", "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED");
        expected.put("the same two levels starred, unfiltered — the control",
                "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED");
        assertEquals(expected, actual, "the rest of the SumOfProducts operand gate chain");
    }

    /** The same verdict on the two-level repeatable fixture, which BASKET has no shape for. */
    private static String nestedVerdict(String operands) {
        return KernelLaws.projectVerdict("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And SumOfProducts(" + operands + ") > 0");
    }

    /** The COMPLETE class list for one operand pair on {@link #BASKET} — a single code would hide a co-fire. */
    private static String verdict(String operands) {
        return KernelLaws.verdictModel(BASKET, "/Basket", "../Total",
                "FieldFilled(Total) And SumOfProducts(" + operands + ") > 0");
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
