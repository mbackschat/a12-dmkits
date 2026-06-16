package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The §Part-I case study — one narrative across the whole loop on a subscription model, every step
 * confirmed by the real kernel. Each tagged region is locked into {@code docs/API-GUIDE.md} by
 * {@code DocSnippetFreshnessTest}; running here proves the code (not just compiles).
 */
class CaseStudyTest {

    /** A small subscription model: Tier (enum) + MonthlyFee (money). */
    private static String subscription() {
        return DocumentModelDraft.named("subscription").locale("en_US")
                .rootGroup("Subscription", s -> s
                        .enumField("Tier", e -> e.value("BASIC").value("PRO").value("ENTERPRISE"))
                        .numberField("MonthlyFee", n -> n.fractionDigits(2)))
                .build().toJson();
    }

    @Test
    void bindAndExplore() {
        String subscription = subscription();
        // tag::cs-bind[]
        BoundModel m = BoundModel.ofJson(subscription);          // bound once — kinds, scale, enum domain in hand
        List<FieldInfo> money = m.fields()                       // explore: every money field; Stream.filter IS the matcher
                .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)
                .toList();
        // end::cs-bind[]
        assertFalse(money.isEmpty());
    }

    @Test
    void authorARuleTheCompilerSteersThenLetTheKernelConfirmIt() {
        String subscription = subscription();
        BoundModel m = BoundModel.ofJson(subscription);
        // tag::cs-author[]
        NumberRef fee  = m.num("/Subscription/MonthlyFee");      // knows: NUMBER, scale 2
        EnumRef   tier = m.enumOf("/Subscription/Tier");         // knows: BASIC / PRO / ENTERPRISE
        Rule rule = m.rule(m.group("/Subscription"))
                .name("ProNeedsFee").errorField(fee)
                .when(tier.is("PRO").and(fee.notFilled()))       // a PRO subscription must carry a monthly fee
                .code("PRO_FEE_REQUIRED").severity(Severity.ERROR)
                .message(Dsl.en("A PRO subscription requires a monthly fee."))
                .build();                                        // build-time pre-checks: error field in scope, locale law
        ValidationResult vr = new RuleValidator().check(subscription, rule.draft());   // the REAL kernel confirms it
        // end::cs-author[]
        assertTrue(vr.ok(), () -> "kernel diagnostics: " + vr.diagnostics());
    }

    @Test
    void editStructureAndRuleInOneSessionThenWriteBack() {
        String subscription = subscription();
        // tag::cs-edit[]
        EditSession s = EditSession.read(subscription);
        GroupEditor sub = s.group("/Subscription");
        sub.addField("Discount", FieldKind.NUMBER);              // structure edit (modelkit)
        NumberRef discount = sub.num("Discount");                // a ref to the field just created
        sub.addRule(r -> r.name("DiscountNonNeg").errorField(discount)
                .when(discount.filled().and(discount.lt(0)))     // rule (rulekit) over the new field
                .code("DISCOUNT_NEG").severity(Severity.ERROR)
                .message(Dsl.en("Discount must not be negative.")).build());
        String updated = s.validate().toJson();                  // one terminal kernel gate, then write back
        // end::cs-edit[]
        assertTrue(updated.contains("Discount") && updated.contains("DiscountNonNeg"));
    }

    @Test
    void runItAgainstDataToSeeItFire() {
        String updated = withDiscountRule();
        // tag::cs-run[]
        // does it actually FIRE? — the empirical check a static "is it valid?" can't give
        EvalResult clean = KernelAdapter.ofJson(updated)
                .evaluate(Map.of("Subscription", Map.of("Discount", "0")), Locale.US);
        assertFalse(clean.firedCodes().contains("DISCOUNT_NEG"));        // a compliant instance — silent

        EvalResult violating = KernelAdapter.ofJson(updated)
                .evaluate(Map.of("Subscription", Map.of("Discount", "-5")), Locale.US);
        assertTrue(violating.firedCodes().contains("DISCOUNT_NEG"));     // a negative discount — the rule fires
        // end::cs-run[]
    }

    private static String withDiscountRule() {
        EditSession s = EditSession.read(subscription());
        GroupEditor sub = s.group("/Subscription");
        sub.addField("Discount", FieldKind.NUMBER);
        NumberRef discount = sub.num("Discount");
        sub.addRule(r -> r.name("DiscountNonNeg").errorField(discount)
                .when(discount.filled().and(discount.lt(0)))
                .code("DISCOUNT_NEG").severity(Severity.ERROR)
                .message(Dsl.en("Discount must not be negative.")).build());
        return s.validate().toJson();
    }
}
