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
 * F10 read tail — the {@code Length(field)} operand wrapper (kernel {@code IFieldIdOperation} named
 * {@code Length}; the dominant wrapper in the corpus). The DSL already has {@code StringRef.length()};
 * this wires the converter to recognise {@code Length} on both sides of a comparison. Own domain: the
 * order fixture's string fields.
 */
class LengthReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel order() {
        return BoundModel.ofJson(read("models/order.dm.json"));
    }

    @Test
    void roundTripsLengthOnTheLeftAndRight() {
        BoundModel m = order();
        // Length([CustomerName]) > 5
        Condition left = m.str("/Order/CustomerName").length().gt(5);
        // [Quantity] <= Length([ProductCode])  — Length as the right operand of a number comparison
        Condition right = m.field("/Order/CustomerName").filled()
                .and(m.num("/Order/Quantity").le(m.str("/Order/ProductCode").length()));

        var cases = new java.util.LinkedHashMap<String, Condition>();
        cases.put("/Order/LenLeftRule", left);
        cases.put("/Order/LenRightRule", right);
        BoundModel withRules = m
                .add(orderRule(m, "LenLeftRule", left))
                .add(orderRule(m, "LenRightRule", right));
        KernelAdapter adapter = KernelAdapter.of(withRules);

        assertTrue(RENDER.render(left).contains("Length(/Order/CustomerName)"), RENDER.render(left));
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.read(fullName))),
                        () -> "Length round-trip mismatch for " + fullName));
    }

    @Test
    void lengthComparisonIsKernelValid() {
        BoundModel m = order();
        Condition c = m.str("/Order/CustomerName").length().gt(5);
        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "LenLeftRule", "../CustomerName", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule orderRule(BoundModel m, String name, Condition cond) {
        return m.rule(m.group("/Order")).name(name).errorField(m.field("/Order/CustomerName")).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = LengthReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
