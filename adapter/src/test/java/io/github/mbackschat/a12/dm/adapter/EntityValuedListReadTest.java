package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — the field-list × value-list quantifiers where the value list is <b>entity-valued</b>
 * (field references / repetition lists, not constants), kernel {@code IEntityListAndValueListCondition}
 * with an entity value list. Covers a field checked against a wildcard repetition list, and a field
 * checked against another field. Own domain: the order fixture's string fields.
 */
class EntityValuedListReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel order() {
        return BoundModel.ofJson(read("models/order.dm.json"));
    }

    @Test
    void roundTripsEntityValuedValueLists() {
        BoundModel m = order();
        // [CustomerName] In (the Sku values across all Items)
        Condition vsList = Dsl.atLeastOneFieldValueIncludedInValueList(
                List.<FieldOperand>of(m.flatten("/Order/Items*/Sku")), m.field("/Order/CustomerName"));
        // [CustomerName] In [ProductCode] — field vs a single other field
        Condition vsField = Dsl.noFieldValueIncludedInValueList(
                List.<FieldOperand>of(m.field("/Order/ProductCode")), m.field("/Order/CustomerName"));

        var cases = new java.util.LinkedHashMap<String, Condition>();
        cases.put("/Order/InItemsRule", vsList);
        cases.put("/Order/NotProductRule", vsField);
        BoundModel withRules = m
                .add(orderRule(m, "InItemsRule", vsList))
                .add(orderRule(m, "NotProductRule", vsField));
        KernelAdapter adapter = KernelAdapter.of(withRules);

        assertTrue(RENDER.render(vsList).contains("In /Order/Items*/Sku"), RENDER.render(vsList));
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.read(fullName))),
                        () -> "entity-valued value-list round-trip mismatch for " + fullName));
    }

    @Test
    void entityValuedValueListIsKernelValid() {
        BoundModel m = order();
        Condition c = Dsl.atLeastOneFieldValueIncludedInValueList(
                List.<FieldOperand>of(m.flatten("/Order/Items*/Sku")), m.field("/Order/CustomerName"));
        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "InItemsRule", "../CustomerName", RENDER.render(c), "C", "ERROR"));
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
        try (InputStream in = EntityValuedListReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
