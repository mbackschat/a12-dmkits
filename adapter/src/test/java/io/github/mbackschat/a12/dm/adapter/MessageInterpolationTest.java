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
 * The BA's real deliverable is the <b>error text the end-user reads</b> — and with message interpolation
 * ({@code $Field$}, {@code $Field.value$}) the authored template is not what renders. This locks that
 * {@link KernelAdapter#evaluate} surfaces the kernel's <em>interpolated</em> message (its {@code
 * IMessage.getErrorText}), not the raw template, so {@code model eval} / {@code rule test} can answer
 * "here is exactly what the user will see" (FEATURE-GAPS G1).
 */
class MessageInterpolationTest {

    /** A rule whose message embeds the value token {@code $Discount.value$} — interpolated against the doc. */
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
                .message(Dsl.en("Discount of $Discount.value$ must not be negative.")).build());
        return s.validate().toJson();
    }

    private static Map<String, Object> orderWith(String discount) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("Discount", discount);
        return Map.of("Order", order);
    }

    @Test
    void firedMessageCarriesTheInterpolatedEndUserText() {
        EvalResult bad = KernelAdapter.ofJson(model()).evaluate(orderWith("-5"), Locale.US);
        EvalMessage fired = bad.messages().stream()
                .filter(m -> m.rulePath().filter("/Order/DiscountNonNeg"::equals).isPresent())
                .findFirst().orElseThrow(() -> new AssertionError("the rule did not fire"));

        assertTrue(fired.message().contains("-5"),
                "the $Discount.value$ token must be interpolated to the actual value: " + fired.message());
        assertFalse(fired.message().contains("$"),
                "no raw interpolation token may remain in the end-user text: " + fired.message());
    }
}
