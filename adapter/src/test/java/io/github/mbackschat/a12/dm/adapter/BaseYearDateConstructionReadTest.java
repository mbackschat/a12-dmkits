package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * {@code Date(day, month)} — the year-less construction whose year is the model's Base Year (KF403, IG163). It is
 * authored through the typed surface, accepted by the kernel when the model declares a Base Year and refused with
 * {@code MVK_MISSING_YEAR} when it does not, and read back from the kernel model to the same rendering, which is
 * the read path a three-component-only record could not represent. Own domain: a policy's fixed renewal day.
 */
class BaseYearDateConstructionReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String policyJson(boolean withBaseYear) {
        var draft = DocumentModelDraft.named("renewal-day").locale("en_US");
        if (withBaseYear) draft = draft.baseYear(2024);
        return draft.rootGroup("Policy", p -> p.dateField("Renewal", d -> d.format("yyyy-MM-dd")))
                .build().toJson();
    }

    private static Condition renewalOnTheFifteenthOfOctober(BoundModel m) {
        DateExpr fixed = Dsl.date(Dsl.temporalComponent("15"), Dsl.temporalComponent("10"));
        return m.date("/Policy/Renewal").filled().and(m.date("/Policy/Renewal").ne(fixed));
    }

    @Test
    void rendersTwoComponentsAndTheKernelAdmitsItOnlyWithABaseYear() {
        BoundModel m = BoundModel.ofJson(policyJson(true));
        String dsl = RENDER.render(renewalOnTheFifteenthOfOctober(m));
        assertTrue(dsl.contains("Date(\"15\", \"10\")"), dsl);
        var admitted = new RuleValidator().check(policyJson(true),
                new DraftRule("/Policy", "R", "../Renewal", dsl, "C", "ERROR"));
        assertTrue(admitted.ok(), () -> "expected kernel acceptance with a Base Year: " + admitted.diagnostics());
        var refused = new RuleValidator().check(policyJson(false),
                new DraftRule("/Policy", "R", "../Renewal", dsl, "C", "ERROR"));
        assertTrue(refused.diagnostics().stream().anyMatch(d -> d.code().orElse("").equals("MVK_MISSING_YEAR")),
                () -> "expected MVK_MISSING_YEAR without a Base Year: " + refused.diagnostics());
    }

    @Test
    void readsBackFromTheKernelModel() {
        BoundModel m = BoundModel.ofJson(policyJson(true));
        Condition condition = renewalOnTheFifteenthOfOctober(m);
        Rule rule = m.rule(m.group("/Policy")).name("FixedRenewal").errorField(m.field("/Policy/Renewal"))
                .when(condition).code("C").severity(Severity.ERROR).message(en("Check the renewal day.")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(rule));
        ReadResult read = adapter.read("/Policy/FixedRenewal");
        Condition converted = assertInstanceOf(ReadResult.Converted.class, read,
                () -> "expected a converted read, got: " + read).condition();
        assertEquals(RENDER.render(condition), RENDER.render(converted));
    }
}
