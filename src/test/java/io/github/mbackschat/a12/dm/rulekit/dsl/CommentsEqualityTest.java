package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * Two identically built rules are equal, comments included: {@link Rule} is a record, so its {@link Comments}
 * component decides, and an identity-keyed side-channel compared by identity made every pair unequal. Each
 * unequal row is the control that the equality still reads the comment text and the commented node.
 */
class CommentsEqualityTest {

    private static final BoundModel M = Fixtures.subscription();
    private static final NumberRef FEE = M.num("/Subscription/Addons/MonthlyFee");

    private static Rule rule(String rootComment, String branchComment, boolean onCap) {
        Condition presence = FEE.filled();
        Condition cap = FEE.gt(1000);
        return M.rule(M.group("/Subscription/Addons")).name("FeeCap").code("FEE_CAP").errorField(FEE.field())
                .when(presence.and(cap)).comment(rootComment).comment(onCap ? cap : presence, branchComment)
                .message(Dsl.en("The fee is too high."), Dsl.de("Die Gebühr ist zu hoch.")).build();
    }

    @Test
    void identicallyBuiltRulesAreEqualAndAnyCommentDifferenceSeparatesThem() {
        Rule rule = rule("cap", "present", false);
        Rule same = rule("cap", "present", false);
        assertEquals(rule, same);
        assertEquals(rule.hashCode(), same.hashCode());
        assertEquals(Comments.none(), Comments.none());

        assertNotEquals(rule, rule("cap", "set", false));
        assertNotEquals(rule, rule("cap", "present", true));
    }
}
