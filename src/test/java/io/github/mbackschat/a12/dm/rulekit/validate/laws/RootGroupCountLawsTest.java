package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS — {@code NumberOfFilledGroups} DOES accept a root group, as the sole starred operand
 * (source {@code CheckFeldListenAnzahlOpImpl} {@code checkNoRootGroupWhenMoreThanOneParameter}). This
 * corrects the catalog's earlier "no root groups" constraint.
 *
 * <p>Also the accept half of the group-cardinality correction (KERNEL-FINDINGS §9 "group cardinality",
 * corrected 2026-07-09): a minItems-style rule over the starred single-group count is kernel-valid in both
 * anchorings — error field INSIDE the counted group (unguarded), or outside behind a {@code FieldFilled}
 * guard. The firing half (fires at 0 and 1 rows, silent at 2) is {@code adapter.laws.NumberOfFilledGroupsStarDiffTest}.
 */
class RootGroupCountLawsTest {

    private static final String MULTIFORM = ModelDrafts.repeatableRoot().toJson();

    @Test
    void numberOfFilledGroupsAcceptsASoleStarredRootGroup() {
        KernelLaws.acceptsModel(MULTIFORM, "/Header", "../Title",
                "FieldFilled(Title) And NumberOfFilledGroups(/Persons*) > 0");
    }

    @Test
    void aMinItemsRuleOverTheStarredCountIsKernelValidInBothAnchorings() {
        KernelLaws.acceptsOrder("/Order", "../Items/Sku", "NumberOfFilledGroups(Items*) < 2");
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And NumberOfFilledGroups(Items*) < 2");
    }

    @Test
    void aStarredRepeatableGroupCanBeMixedWithAFixedGroup() {
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And NumberOfFilledGroups(Items*, ShippingAddress) < 2");
    }

    @Test
    void starringANonRepeatableRootGroupIsRejectedAsAnInvalidWildcard() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And NumberOfFilledGroups(/Order*) < 2", "MVK_INVALID_WILDCARD");
    }
}
