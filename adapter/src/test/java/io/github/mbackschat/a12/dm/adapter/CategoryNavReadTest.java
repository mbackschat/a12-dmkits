package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10-B — enum <b>category-navigation</b> comparisons ({@code [field -> Category] == / != "value"}).
 * The kernel carries the category on the field reference's {@code IEntityId.getCategoryName()}; the
 * converter now routes those to {@code EnumRef.category(name).is/isNot(value)} (DSL already exists)
 * instead of mis-validating the category value against the raw enum domain. Subscription's {@code Tier}
 * enum declares a {@code Pricing} category (FREE/PAID).
 */
class CategoryNavReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel subscription() {
        return BoundModel.ofJson(read("models/subscription.dm.json"));
    }

    @Test
    void roundTripsCategoryNavigationComparison() {
        BoundModel m = subscription();
        Condition isPaid = m.enumOf("/Subscription/Tier").category("Pricing").is("PAID");
        Condition notFree = m.enumOf("/Subscription/Tier").category("Pricing").isNot("FREE");

        var cases = new java.util.LinkedHashMap<String, Condition>();
        cases.put("/Subscription/PaidRule", isPaid);
        cases.put("/Subscription/NotFreeRule", notFree);
        BoundModel withRules = m
                .add(rule(m, "PaidRule", isPaid))
                .add(rule(m, "NotFreeRule", notFree));
        KernelAdapter adapter = KernelAdapter.of(withRules);

        assertTrue(RENDER.render(isPaid).contains("[/Subscription/Tier -> Pricing] == \"PAID\""), RENDER.render(isPaid));
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.read(fullName))),
                        () -> "category-nav round-trip mismatch for " + fullName));
    }

    @Test
    void categoryNavigationComparisonIsKernelValid() {
        ValidationResult r = new RuleValidator().check(read("models/subscription.dm.json"),
                new DraftRule("/Subscription", "PaidRule", "../Tier",
                        "[/Subscription/Tier -> Pricing] == \"PAID\"", "C", "ERROR"));
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

    private static String read(String path) {
        try (InputStream in = CategoryNavReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
