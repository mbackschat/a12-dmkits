package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Custom conditions ({@code CustomCondition <Name>}, kernel {@code IApplicationCondition}) are now
 * <b>converted</b>, not Opaque — they're name-only, and the typed {@code CustomConditionRef} captures
 * that exactly. So a rule of the form {@code X And CustomCondition Y} reads as a fully typed AST and
 * can be edited end-to-end: rename the custom condition, or swap the {@code X} side, then save.
 */
class CustomConditionReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel subscription() {
        return BoundModel.ofJson(TestResources.text("models/subscription.dm.json"));
    }

    @Test
    void readsXAndCustomConditionAsTypedAndRoundTrips() {
        BoundModel m = subscription();
        Condition c = m.field("/Subscription/Tier").filled().and(Dsl.customCondition("ExternalEligibility"));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "EligibilityRule", c)));

        Condition got = converted(adapter.read("/Subscription/EligibilityRule"));
        assertTrue(RENDER.render(got).contains("CustomCondition ExternalEligibility"), RENDER.render(got));
        assertEquals(RENDER.render(c), RENDER.render(got));   // typed, not opaque; round-trips
    }

    @Test
    void editsRenameTheCustomConditionAndSwapTheXSide() {
        BoundModel m = subscription().add(rule(subscription(),
                "EligibilityRule", subscription().field("/Subscription/Tier").filled()
                        .and(Dsl.customCondition("ExternalEligibility"))));

        // rename the custom condition (keep X)
        BoundModel renamed = m.replaceCondition("/Subscription/EligibilityRule",
                m.field("/Subscription/Tier").filled().and(Dsl.customCondition("RenamedCheck")));
        String afterRename = RENDER.render(converted(KernelAdapter.of(renamed).read("/Subscription/EligibilityRule")));
        assertTrue(afterRename.contains("CustomCondition RenamedCheck"), afterRename);
        assertFalse(afterRename.contains("ExternalEligibility"), afterRename);

        // swap the X side (keep the custom condition) — still references the error field Tier
        BoundModel swapped = m.replaceCondition("/Subscription/EligibilityRule",
                m.enumOf("/Subscription/Tier").is("PRO").and(Dsl.customCondition("ExternalEligibility")));
        String afterSwap = RENDER.render(converted(KernelAdapter.of(swapped).read("/Subscription/EligibilityRule")));
        assertTrue(afterSwap.contains("[/Subscription/Tier] == \"PRO\""), afterSwap);
        assertTrue(afterSwap.contains("CustomCondition ExternalEligibility"), afterSwap);
    }

    @Test
    void xAndCustomConditionIsKernelValid() {
        BoundModel m = subscription();
        Condition c = m.field("/Subscription/Tier").filled().and(Dsl.customCondition("ExternalEligibility"));
        ValidationResult r = new RuleValidator().check(TestResources.text("models/subscription.dm.json"),
                new DraftRule("/Subscription", "EligibilityRule", "../Tier", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, Condition cond) {
        return m.rule(m.group("/Subscription")).name(name).errorField(m.field("/Subscription/Tier")).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x"), de("y")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
