package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The typed-builder comment authoring path (task #30): {@code Rule.Builder.comment(...)} attaches a
 * {@code ;;} comment to the rule's condition through the {@link Comments} side-channel, so the typed
 * write ({@code rule.render()}) and the validator ({@code rule.draft()}) both carry it — no
 * hand-written {@code ;;} string. {@code comment(String)} comments the whole rule; {@code
 * comment(Condition, String)} targets one branch by identity.
 */
class RuleCommentTest {

    private final BoundModel m = Fixtures.subscription();
    private final String modelJson = Fixtures.subscriptionJson();

    @Test
    void commentsTheWholeRuleAndRendersItAsALeadingLine() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(fee)
                .when(fee.filled().and(fee.gt(1000)))
                .comment("cap the monthly fee")
                .code("FEE_CAP")
                .severity(Severity.ERROR)
                .message(en("The fee exceeds the cap."), de("Die Gebühr überschreitet das Limit."))
                .build();

        assertEquals(
                ";; cap the monthly fee\nFieldFilled(MonthlyFee) And [MonthlyFee] > 1000",
                rule.render());

        // the kernel accepts the commented rule (the ;; line is concrete syntax it tolerates)
        ValidationResult vr = new RuleValidator().check(modelJson, rule.draft());
        assertTrue(vr.ok(), () -> "kernel should accept the commented rule, got: " + vr.diagnostics());
    }

    @Test
    void targetsAnIndividualBranchByIdentity() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Condition tooHigh = fee.gt(1000);
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(fee)
                .when(fee.filled().and(tooHigh))
                .comment(tooHigh, "the cap")
                .code("FEE_CAP")
                .severity(Severity.ERROR)
                .message(en("The fee exceeds the cap."), de("Die Gebühr überschreitet das Limit."))
                .build();

        // the comment sits ahead of the targeted branch, not the whole condition
        assertEquals(
                "FieldFilled(MonthlyFee) And ;; the cap\n[MonthlyFee] > 1000",
                rule.render());
    }

    @Test
    void commentsAfterABranchAndTheKernelAccepts() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Condition tooHigh = fee.gt(1000);
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(fee)
                .when(fee.filled().and(tooHigh))
                .commentAfter(tooHigh, "hard cap per policy")
                .code("FEE_CAP")
                .severity(Severity.ERROR)
                .message(en("The fee exceeds the cap."), de("Die Gebühr überschreitet das Limit."))
                .build();

        assertEquals(
                "FieldFilled(MonthlyFee) And [MonthlyFee] > 1000 ;; hard cap per policy\n",
                rule.render());

        ValidationResult vr = new RuleValidator().check(modelJson, rule.draft());
        assertTrue(vr.ok(), () -> "kernel should accept the trailing-commented rule, got: " + vr.diagnostics());
    }

    @Test
    void persistsTheCommentThroughTheTypedWritePath() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        String json = io.github.mbackschat.a12.dm.edit.EditSession.read(modelJson)
                .addRule(bm -> bm.rule(bm.group("/Subscription/Addons"))
                        .name("FeeCapRule")
                        .errorField(bm.num("/Subscription/Addons/MonthlyFee"))
                        .when(bm.num("/Subscription/Addons/MonthlyFee").filled()
                                .and(bm.num("/Subscription/Addons/MonthlyFee").gt(1000)))
                        .comment("cap the monthly fee")
                        .code("FEE_CAP")
                        .severity(Severity.ERROR)
                        .message(en("The fee exceeds the cap."), de("Die Gebühr überschreitet das Limit."))
                        .build())
                .validate().toJson();

        assertTrue(json.contains(";; cap the monthly fee"),
                "the authored comment is persisted by the typed write path");
    }

    @Test
    void rendersWithoutCommentWhenNoneAuthored() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(fee)
                .when(fee.filled().and(fee.gt(1000)))
                .code("FEE_CAP")
                .severity(Severity.ERROR)
                .message(en("The fee exceeds the cap."), de("Die Gebühr überschreitet das Limit."))
                .build();

        assertEquals("FieldFilled(MonthlyFee) And [MonthlyFee] > 1000", rule.render());
    }
}
