package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

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
 * F10-A — an aggregate over an explicit <b>multi-field</b> scope (kernel {@code IEntityListOperation}),
 * e.g. {@code Sum(a, b)} across two distinct number fields rather than a single wildcard repetition
 * list. The DSL already takes a {@code FieldOperand} list; this widens the converter's single-field
 * guard. Own domain: the order fixture's number fields.
 */
class MultiFieldSumReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel order() {
        return BoundModel.ofJson(read("models/order.dm.json"));
    }

    @Test
    void roundTripsSumOverTwoExplicitFields() {
        BoundModel m = order();
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.sum(m.field("/Order/Quantity"), m.field("/Order/BackorderQuantity")).gt(100));

        Rule r = m.rule(m.group("/Order")).name("SumTwoRule").errorField(m.field("/Order/CustomerName"))
                .when(c).code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));

        assertTrue(RENDER.render(c).contains("Sum(/Order/Quantity, /Order/BackorderQuantity)"), RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/SumTwoRule"))));
    }

    @Test
    void multiFieldSumIsKernelValid() {
        BoundModel m = order();
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.sum(m.field("/Order/Quantity"), m.field("/Order/BackorderQuantity")).gt(100));
        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "SumTwoRule", "../CustomerName", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = MultiFieldSumReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
