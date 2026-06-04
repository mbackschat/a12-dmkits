package io.github.mbackschat.a12.rulekit.dsl;

import static io.github.mbackschat.a12.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

/**
 * Fork #9 / F3a path policy — a rule renders paths <em>relative to its group</em> (the kernel's
 * authoring norm: bare under the group, {@code ../} up to a sibling), and the relative output is
 * kernel-accepted. Standalone {@code render(condition)} stays absolute (no group context).
 */
class PathPolicyTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String JSON = Fixtures.subscriptionJson();

    @Test
    void rendersFieldsBareRelativeToTheRuleGroup() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeRule").errorField(fee).when(fee.filled().and(fee.le(0)))
                .code("FEE").severity(Severity.ERROR).message(en("x"), de("y")).build();

        assertEquals("FieldFilled(MonthlyFee) And [MonthlyFee] <= 0", rule.render());
        assertTrue(new RuleValidator().check(JSON, rule.draft()).ok(),
                "the relative rendering must be kernel-accepted");
    }

    @Test
    void rendersASiblingGroupReferenceWithParentNavigation() {
        // rule at /Subscription/Addons referencing /Subscription/Tier → ../Tier
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("TierGuardedFeeRule").errorField(fee)
                .when(fee.filled().and(m.field("/Subscription/Tier").filled()))
                .code("FEE").severity(Severity.ERROR).message(en("x"), de("y")).build();

        assertEquals("FieldFilled(MonthlyFee) And FieldFilled(../Tier)", rule.render());
        assertTrue(new RuleValidator().check(JSON, rule.draft()).ok(),
                "../ sibling navigation must be kernel-accepted");
    }

    @Test
    void standaloneRenderStaysAbsolute() {
        // No rule-group context → absolute (catalog/laws rendering is unaffected).
        Condition c = m.field("/Subscription/Addons/MonthlyFee").filled();
        assertEquals("FieldFilled(/Subscription/Addons/MonthlyFee)", new DslRenderer().render(c));
    }
}
