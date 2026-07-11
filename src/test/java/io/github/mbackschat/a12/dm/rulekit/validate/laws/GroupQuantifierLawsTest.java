package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §1 — group-list quantifier slot laws.
 *
 * <ul>
 *   <li><b>Asterisk bar</b> (BA L2399): {@code NotAllGroupsFilled}/{@code GroupsNotCollectivelyFilled}/
 *       {@code AllGroupsFilled} arguments may not use an asterisk ({@code MVK_NO_WILDCARDS_G_ALLOWED};
 *       source {@code CheckEntityListenUtils.checkNoWildCardsInGroup}).</li>
 *   <li><b>rootGroup bar</b> (BA L2397-2399, source + lock 2026-07-12, graduates IG65): the SAME three
 *       bar <em>any</em> rootGroup argument ({@code MVK_ROOT_GROUP_REFERENCED}, via
 *       {@code checkNotRootGroupReferenced}), while {@code NoGroupFilled}/{@code AtLeastOneGroupFilled}
 *       accept a <em>sole</em> rootGroup and reject a rootGroup <em>with another</em> parameter
 *       ({@code MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS}, via {@code checkNoRootGroupWhenMoreThanOneParameter}).</li>
 * </ul>
 *
 * <p>The rootGroup bar needs a <b>multi-root</b> fixture whose referenced roots are <b>non-repeatable</b>
 * (a repeatable root would trip the needs-asterisk check first, and an asterisk is itself barred). This
 * insurance fixture has two non-repeatable roots ({@code /Policy}, {@code /Claim}) plus a non-root
 * subgroup ({@code /Policy/Coverage}) for the root-with-another case; rows guard the error field with
 * {@code FieldFilled(PolicyNo)}.
 */
class GroupQuantifierLawsTest {

    private static final String TWO_ROOTS = DocumentModelDraft.named("claimform").locale("en_US")
            .rootGroup("Policy", p -> p
                    .field("PolicyNo", FieldKind.STRING)
                    .group("Coverage", c -> c.field("Amount", FieldKind.NUMBER)))
            .rootGroup("Claim", c -> c.field("ClaimNo", FieldKind.STRING))
            .build().toJson();

    @Test
    void notAllGroupsFilledRejectsAnAsteriskArgument() {
        KernelLaws.rejectsOrder("/Order", "../ShippingAddress/Street",
                "NotAllGroupsFilled(Items*, ShippingAddress)", "MVK_NO_WILDCARDS_G_ALLOWED");
    }

    // --- rootGroup bar: the THREE reject ANY rootGroup argument (MVK_ROOT_GROUP_REFERENCED) ---

    @Test
    void allGroupsFilledBarsARootGroup() {
        KernelLaws.rejectsModel(TWO_ROOTS, "/Policy", "../PolicyNo",
                "FieldFilled(PolicyNo) And AllGroupsFilled(/Claim)", "MVK_ROOT_GROUP_REFERENCED");
    }

    @Test
    void notAllGroupsFilledBarsARootGroup() {
        KernelLaws.rejectsModel(TWO_ROOTS, "/Policy", "../PolicyNo",
                "FieldFilled(PolicyNo) And NotAllGroupsFilled(/Claim)", "MVK_ROOT_GROUP_REFERENCED");
    }

    @Test
    void groupsNotCollectivelyFilledBarsARootGroup() {
        KernelLaws.rejectsModel(TWO_ROOTS, "/Policy", "../PolicyNo",
                "FieldFilled(PolicyNo) And GroupsNotCollectivelyFilled(/Claim)", "MVK_ROOT_GROUP_REFERENCED");
    }

    // --- NoGroupFilled / AtLeastOneGroupFilled ACCEPT a sole rootGroup, reject rootGroup + another ---

    @Test
    void noGroupFilledAcceptsASoleRootGroup() {
        KernelLaws.acceptsModel(TWO_ROOTS, "/Policy", "../PolicyNo",
                "FieldFilled(PolicyNo) And NoGroupFilled(/Claim)");
    }

    @Test
    void atLeastOneGroupFilledAcceptsASoleRootGroup() {
        KernelLaws.acceptsModel(TWO_ROOTS, "/Policy", "../PolicyNo",
                "FieldFilled(PolicyNo) And AtLeastOneGroupFilled(/Claim)");
    }

    @Test
    void noGroupFilledRejectsARootGroupWithAnotherParameter() {
        KernelLaws.rejectsModel(TWO_ROOTS, "/Policy", "../PolicyNo",
                "FieldFilled(PolicyNo) And NoGroupFilled(/Claim, /Policy/Coverage)",
                "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS");
    }

    @Test
    void atLeastOneGroupFilledRejectsARootGroupWithAnotherParameter() {
        KernelLaws.rejectsModel(TWO_ROOTS, "/Policy", "../PolicyNo",
                "FieldFilled(PolicyNo) And AtLeastOneGroupFilled(/Claim, /Policy/Coverage)",
                "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS");
    }
}
