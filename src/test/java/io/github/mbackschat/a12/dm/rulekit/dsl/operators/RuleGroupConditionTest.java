package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The {@code RuleGroup} self-reference (DEV-API-GAPS shape (4); the kernel's {@code RegelKontext},
 * KERNEL-FINDINGS "Condition directives" / IF63). {@code Dsl.ruleGroup()} denotes the rule's OWN group
 * for {@code GroupFilled}/{@code GroupNotFilled}; it renders the reserved KEYWORD (EN {@code RuleGroup},
 * DE {@code RegelKontext}), never a qualified path. The stored form is pinned by the interpreter
 * differential {@code RuleGroupSelfReferenceDiffTest} + accepted by {@code DirectiveLawsTest}; this
 * proves the typed handle renders to it and the real kernel accepts the typed authoring.
 */
class RuleGroupConditionTest {

    private static final BoundModel m = Fixtures.order();

    @Test
    void ruleGroupFilledRendersTheKeywordBilingually() {
        Condition c = Dsl.ruleGroup().filled();
        assertEquals("GroupFilled(RuleGroup)", new DslRenderer().render(c));
        assertEquals("KontextAngegeben(RegelKontext)", new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void ruleGroupNotFilledRendersTheKeywordBilingually() {
        Condition c = Dsl.ruleGroup().notFilled();
        assertEquals("GroupNotFilled(RuleGroup)", new DslRenderer().render(c));
        assertEquals("KontextNichtAngegeben(RegelKontext)", new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void theAttachmentShapeComposesLikeTheDiffTest() {
        // RuleGroupSelfReferenceDiffTest's flagship: GroupFilled(RuleGroup) And NotExactlyOneFieldFilled(Count, UnitWeight)
        Condition c = Dsl.ruleGroup().filled()
                .and(Dsl.notExactlyOneFieldFilled(m.num("/Order/Items/Count").field(),
                        m.num("/Order/Items/UnitWeight").field()));
        assertEquals("GroupFilled(RuleGroup) And NotExactlyOneFieldFilled(/Order/Items/Count, /Order/Items/UnitWeight)",
                new DslRenderer().render(c));
    }

    @Test
    void transformPreservesTheSelfReference() {
        Condition c = Dsl.ruleGroup().filled();
        assertEquals(c, c.transform(node -> node));   // leaf: untouched transform returns it
    }

    @Test
    void theRealKernelAcceptsTypedRuleGroupFilled() {
        // round-trip: author typed → render → the REAL kernel accepts it (RuleGroup on a repeatable
        // iteration group is the "is this row present" guard). Items is repeatable in the fixture.
        String rendered = new DslRenderer().render(Dsl.ruleGroup().filled());
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/Order/Items", "RuleGroupRule", "../Count", rendered, "RG", "ERROR"));
        assertTrue(vr.ok(), () -> "kernel rejected typed GroupFilled(RuleGroup): " + vr.diagnostics());
    }
}
