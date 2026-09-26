package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * F10 read tail — the filled-count aggregates over a single wildcard scope (kernel
 * {@code IEntityListOperation}): {@code NumberOfFilledFields} over a flattened repetition list (e.g.
 * counting filled reps of an {@code IndexFields} index field), and {@code NumberOfFilledGroups} over a
 * single repeatable group ({@code Group*}, counting that group's instantiated repetitions). The latter
 * corrected the {@code CountAggregate} ≥2-groups invariant — the kernel accepts the wildcard single-group
 * form, confirmed by {@link #numberOfFilledGroupsIsKernelValid()}.
 */
class FilledCountReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel order() {
        return BoundModel.ofJson(TestResources.text("models/order.dm.json"));
    }

    @Test
    void roundTripsNumberOfFilledFieldsOverARepetitionList() {
        BoundModel m = order();
        // [CustomerName] filled And NumberOfFilledFields(/Order/Items*/Count) > 0
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.numberOfFilledFields(m.flatten("/Order/Items*/Count")).gt(0));

        Rule r = m.rule(m.group("/Order")).name("FilledCountRule").errorField(m.field("/Order/CustomerName"))
                .when(c).code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));

        assertTrue(RENDER.render(c).contains("NumberOfFilledFields(/Order/Items*/Count)"), RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/FilledCountRule"))));
    }

    @Test
    void roundTripsNumberOfFilledGroupsOverARepeatableGroup() {
        BoundModel m = order();
        // [CustomerName] filled And NumberOfFilledGroups(/Order/Items*) > 1   (Items repeats)
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.numberOfFilledGroups(m.group("/Order/Items")).gt(1));

        Rule r = m.rule(m.group("/Order")).name("GroupCountRule").errorField(m.field("/Order/CustomerName"))
                .when(c).code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));

        assertTrue(RENDER.render(c).contains("NumberOfFilledGroups(/Order/Items*)"), RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/GroupCountRule"))));
    }

    @Test
    void roundTripsNumberOfFilledGroupsOverMixedRepeatableAndFixedGroups() {
        BoundModel m = order();
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.numberOfFilledGroups(
                        m.group("/Order/Items"),
                        m.group("/Order/ShippingAddress")).lt(2));

        Rule r = m.rule(m.group("/Order")).name("MixedGroupCountRule")
                .errorField(m.field("/Order/CustomerName"))
                .when(c).code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));

        assertEquals(
                "FieldFilled(/Order/CustomerName) And NumberOfFilledGroups(/Order/Items*, /Order/ShippingAddress) < 2",
                RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/MixedGroupCountRule"))));
    }

    @Test
    void numberOfFilledGroupsOverASingleRepeatableGroupIsKernelValid() {
        BoundModel m = order();
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.numberOfFilledGroups(m.group("/Order/Items")).gt(1));
        ValidationResult r = new RuleValidator().check(TestResources.text("models/order.dm.json"),
                new DraftRule("/Order", "GroupCountRule", "../CustomerName", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    @Test
    void roundTripsNumberOfValueInFields() {
        // family 6: NumberOfValueInFields(0 In Quantity, BackorderQuantity) > 0 — count of the two amounts equal to 0.
        BoundModel m = order();
        Condition c = m.field("/Order/Quantity").filled().and(
                Dsl.numberOfValueInFields(0, m.field("/Order/Quantity"), m.field("/Order/BackorderQuantity")).gt(0));
        Rule r = m.rule(m.group("/Order")).name("ValueCountRule").errorField(m.field("/Order/Quantity"))
                .when(c).code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));

        assertTrue(RENDER.render(c).contains("NumberOfValueInFields(0 In /Order/Quantity, /Order/BackorderQuantity)"),
                RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/ValueCountRule"))));
    }

    @Test
    void numberOfFilledFieldsIsKernelValid() {
        BoundModel m = order();
        // the kernel requires the error field to appear in the condition (MVK_ERROR_FIELD_NOT_REFERENCED)
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.numberOfFilledFields(m.flatten("/Order/Items*/Count")).gt(0));
        ValidationResult r = new RuleValidator().check(TestResources.text("models/order.dm.json"),
                new DraftRule("/Order", "FilledCountRule", "../CustomerName", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
