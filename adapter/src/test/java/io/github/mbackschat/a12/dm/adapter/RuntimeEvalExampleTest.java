package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Runtime evaluation — "does this rule FIRE on a document <em>instance</em>?" (the verify-by-execution
 * capability behind the {@code eval-doc} verb). This is distinct from the static "is the rule
 * kernel-valid?" check: it runs the rule over real data and reports which rule <b>codes</b> fired — the
 * angle that catches an inverted-but-valid rule. Backs the API-study runtime-eval example. (The seam
 * itself is exercised deeply by {@code adapter.laws.EvalDocSeamLawsTest}; this is the readable showcase.)
 */
class RuntimeEvalExampleTest {

    /** A tiny model carrying one rule: Discount must not be negative. */
    private static String model() {
        String structure = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o.numberField("Discount", n -> n.fractionDigits(2)))
                .build().toJson();
        EditSession s = EditSession.read(structure);
        GroupEditor order = s.group("/Order");
        NumberRef discount = order.num("Discount");
        order.addRule(r -> r.name("DiscountNonNeg").errorField(discount)
                .when(discount.filled().and(discount.lt(0)))
                .code("DISCOUNT_NEG").severity(Severity.ERROR)
                .message(Dsl.en("Discount must not be negative.")).build());
        return s.validate().toJson();
    }

    /** A document instance: the nested tree the kernel evaluates (fields name-keyed under their group). */
    private static Map<String, Object> orderWith(String discount) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("Discount", discount);
        return Map.of("Order", order);
    }

    @Test
    void theRuleFiresOnlyOnTheViolatingDocument() {
        String model = model();

        // a compliant document — the rule does NOT fire
        EvalResult ok = KernelAdapter.ofJson(model).evaluate(orderWith("0"), Locale.US);
        assertFalse(ok.firedCodes().contains("DISCOUNT_NEG"));

        // a violating document — the rule FIRES
        EvalResult bad = KernelAdapter.ofJson(model).evaluate(orderWith("-5"), Locale.US);
        assertTrue(bad.firedCodes().contains("DISCOUNT_NEG"));
    }
}
