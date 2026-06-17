package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS — group-list quantifier slot law (BA L2399): {@code NotAllGroupsFilled} /
 * {@code GroupsNotCollectivelyFilled} / {@code AllGroupsFilled} arguments may not use an <b>asterisk</b>
 * ({@code MVK_NO_WILDCARDS_G_ALLOWED}). (BA also bars a rootGroup argument for these three — not probed
 * here: the order fixture's single root contains every group, so a rootGroup arg trips the
 * duplicate-reference check first; left BA-stated, unverified.)
 *
 * <p>Source: {@code CheckEntityListenUtils.checkNoWildCardsInGroup}, fired on {@code ref.hasWildCardsInGroup()}.
 */
class GroupQuantifierLawsTest {

    @Test
    void notAllGroupsFilledRejectsAnAsteriskArgument() {
        KernelLaws.rejectsOrder("/Order", "../ShippingAddress/Street",
                "NotAllGroupsFilled(Items*, ShippingAddress)", "MVK_NO_WILDCARDS_G_ALLOWED");
    }
}
