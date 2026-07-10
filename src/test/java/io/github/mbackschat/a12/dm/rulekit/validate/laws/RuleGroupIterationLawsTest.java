package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS — {@code GroupNotFilled(RuleGroup)} is authoring-REJECTED on a <b>repeatable iteration
 * group</b> ({@code MVK_NEG_CONDITION_IN_ITERATION}): a negated presence test on the rule's own repeated
 * context is meaningless per row (every instantiated row IS present). The {@code filled()} polarity is
 * accepted ({@code RuleGroupConditionTest}); this pins its dark twin, previously only prose-noted in the
 * interpreter differential {@code RuleGroupSelfReferenceDiffTest} (never kernel-locked). {@code /Order/Items}
 * is repeatable in the order fixture.
 */
class RuleGroupIterationLawsTest {

    @Test
    void groupNotFilledRuleGroupIsRejectedInAnIterationGroup() {
        KernelLaws.rejectsOrder("/Order/Items", "../Count",
                "GroupNotFilled(RuleGroup)", "MVK_NEG_CONDITION_IN_ITERATION");
    }
}
