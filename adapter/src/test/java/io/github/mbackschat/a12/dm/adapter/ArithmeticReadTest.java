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
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
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
class ArithmeticReadTest {

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
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.read(fullName))),
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
        assertEquals(RENDER.render(both), RENDER.render(converted(adapter.read("/Order/BothRule"))));
    }

    @Test
    void roundTripsOperandListMath() {
        // family 5: Abs(expr) over an arithmetic, and Min/Max over two numeric value expressions.
        BoundModel m = order();
        Condition abs = Dsl.abs(m.num("/Order/Quantity").asNumber().minus(m.num("/Order/UnitPrice").asNumber())).lt(1);
        Condition min = Dsl.min(m.num("/Order/Quantity").asNumber(), m.num("/Order/UnitPrice").asNumber()).gt(0);
        Condition max = Dsl.max(m.num("/Order/Quantity").asNumber(), m.num("/Order/UnitPrice").asNumber()).gt(100);
        assertEquals("Abs([/Order/Quantity] - [/Order/UnitPrice]) < 1", RENDER.render(abs));
        assertEquals("Min([/Order/Quantity], [/Order/UnitPrice]) > 0", RENDER.render(min));
        assertEquals("Max([/Order/Quantity], [/Order/UnitPrice]) > 100", RENDER.render(max));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "AbsRule", abs)).add(orderRule(m, "MinRule", min)).add(orderRule(m, "MaxRule", max)));
        assertEquals(RENDER.render(abs), RENDER.render(converted(adapter.read("/Order/AbsRule"))));
        assertEquals(RENDER.render(min), RENDER.render(converted(adapter.read("/Order/MinRule"))));
        assertEquals(RENDER.render(max), RENDER.render(converted(adapter.read("/Order/MaxRule"))));
    }

    @Test
    void roundTripsToleranceComparison() {
        // [Quantity] DiffersWithToleranceRange5 [BackorderQuantity] — unequal within a tolerance of 5.
        BoundModel m = order();
        Condition c = m.num("/Order/Quantity").differsWithTolerance(
                io.github.mbackschat.a12.dm.rulekit.dsl.Tolerance.RANGE_5, m.num("/Order/BackorderQuantity"));
        assertEquals("[/Order/Quantity] DiffersWithToleranceRange5 [/Order/BackorderQuantity]", RENDER.render(c));
        KernelAdapter adapter = KernelAdapter.of(m.add(orderRule(m, "TolRule", c)));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/TolRule"))));
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

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = ArithmeticReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
