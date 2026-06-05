package io.github.mbackschat.a12.rulekit.dsl;

import static io.github.mbackschat.a12.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.validate.DraftRule;
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
    void aBareNameResolvesUpToAnAncestorGroupsField() {
        // Kernel-verified (KERNEL-FINDINGS §paths): a bare field name resolves against the rule's group
        // AND its ancestors — it walks UP the hierarchy. So bare `[Tier]` from a rule scoped to
        // /Subscription/Addons binds to the ancestor /Subscription/Tier, no `../` or absolute path
        // needed. (Fed as raw condition text: the renderer would emit `../Tier` for an ancestor, but
        // the kernel accepts the bare form too — this locks that kernel behavior, which once tripped an
        // eval judge into false-flagging correct candidates; RESULTS.md 2026-06-05.)
        DraftRule walkUp = new DraftRule("/Subscription/Addons", "WalkUpRule", "../MonthlyFee",
                "GroupFilled(/Subscription/Addons) And [Tier] == \"PRO\" And FieldNotFilled(MonthlyFee)",
                "WALK_UP", "ERROR");
        assertTrue(new RuleValidator().check(JSON, walkUp).ok(),
                "bare [Tier] must resolve up to the ancestor /Subscription/Tier");

        // Control: a bare name found in NO ancestor is rejected — so the acceptance above is real
        // upward resolution, not the kernel ignoring unknown references.
        DraftRule bogus = new DraftRule("/Subscription/Addons", "BogusRule", "../MonthlyFee",
                "[Bogus] == \"PRO\" And FieldNotFilled(MonthlyFee)", "BOGUS", "ERROR");
        assertFalse(new RuleValidator().check(JSON, bogus).ok(),
                "a bare name found in no ancestor must be rejected (MVK_INVALID_ENTITY)");
    }

    @Test
    void standaloneRenderStaysAbsolute() {
        // No rule-group context → absolute (catalog/laws rendering is unaffected).
        Condition c = m.field("/Subscription/Addons/MonthlyFee").filled();
        assertEquals("FieldFilled(/Subscription/Addons/MonthlyFee)", new DslRenderer().render(c));
    }

    @Test
    void singleQuotesAKeywordNamedPathSegment() {
        // A field named like a DSL keyword is a parse error unquoted (MVK_UNEXPECTED_TOKEN, probe-verified);
        // 'Length' is the grammar's escape. Detection uses the catalog keywords ('Length' is one).
        Condition c = new FieldPresenceCondition(
                new FieldRef("/Order/Length", io.github.mbackschat.a12.rulekit.model.ValueKind.NUMBER), true);

        assertEquals("FieldFilled(/Order/'Length')", new DslRenderer().render(c));
        assertEquals("FieldFilled('Length')", new DslRenderer(ConditionLanguage.EN, "/Order").render(c));
    }

    @Test
    void leavesOrdinaryPathSegmentsUnquoted() {
        Condition c = m.field("/Subscription/Addons/MonthlyFee").filled();
        assertEquals("FieldFilled(MonthlyFee)",
                new DslRenderer(ConditionLanguage.EN, "/Subscription/Addons").render(c));
    }
}
