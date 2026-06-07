package io.github.mbackschat.a12.dm.adapter;

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
 * F10-E — arithmetic operands in a comparison (kernel {@code ICompositeOperation}), e.g.
 * {@code [maxLength] < [minFractionalDigits] + 2}. The new {@code Arithmetic} calculation + the
 * {@code NumberExpr.plus/minus/…} builders express it; the converter folds the kernel's operand list.
 * Own domain: the order fixture's number fields.
 */
class ArithmeticIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel order() {
        return BoundModel.ofJson(read("models/order.dm.json"));
    }

    @Test
    void roundTripsArithmeticOperand() {
        BoundModel m = order();
        // [Quantity] < [BackorderQuantity] + 2
        Condition add = m.num("/Order/Quantity").lt(m.num("/Order/BackorderQuantity").asNumber().plus(2));
        // [Quantity] >= [BackorderQuantity] - 1
        Condition sub = m.num("/Order/Quantity").ge(m.num("/Order/BackorderQuantity").asNumber().minus(1));

        var cases = new java.util.LinkedHashMap<String, Condition>();
        cases.put("/Order/AddRule", add);
        cases.put("/Order/SubRule", sub);
        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "AddRule", add)).add(orderRule(m, "SubRule", sub)));

        assertTrue(RENDER.render(add).contains("[/Order/BackorderQuantity] + 2"), RENDER.render(add));
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
                        () -> "arithmetic round-trip mismatch for " + fullName));
    }

    @Test
    void roundTripsArithmeticOnBothSides() {
        // the corpus tail: an arithmetic expression on the LEFT too — ([a] + 1) == ([b] - 2).
        // The left composite isn't a plain field/aggregate, so it took the catch-all before.
        BoundModel m = order();
        Condition both = m.num("/Order/Quantity").asNumber().plus(1)
                .eq(m.num("/Order/BackorderQuantity").asNumber().minus(2));
        KernelAdapter adapter = KernelAdapter.of(m.add(orderRule(m, "BothRule", both)));
        assertEquals(RENDER.render(both), RENDER.render(converted(adapter.ingest("/Order/BothRule"))));
    }

    @Test
    void arithmeticComparisonIsKernelValid() {
        BoundModel m = order();
        Condition c = m.num("/Order/Quantity").lt(m.num("/Order/BackorderQuantity").asNumber().plus(2));
        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "AddRule", "../Quantity", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule orderRule(BoundModel m, String name, Condition cond) {
        return m.rule(m.group("/Order")).name(name).errorField(m.field("/Order/Quantity")).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = ArithmeticIngestTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
