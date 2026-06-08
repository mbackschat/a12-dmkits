package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
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
 * F10-C — field-fill quantifiers over a <b>group scope</b> (kernel {@code IEntityListCondition} on a
 * group): all/any/no fields inside a bare group, a single starred repeatable group, or nested starred
 * groups (e.g. Categories then Values, both starred). The new {@code GroupListRef} keeps every starred
 * segment, so even nested wildcards round-trip without dropping or moving a star (the corruption risk
 * that kept this opaque). Covers bare, single-star, and nested-star scopes.
 */
class GroupScopedFieldFillReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel order() {
        return BoundModel.ofJson(read("models/order.dm.json"));
    }

    /** A model with NESTED repeatable groups (Sections* / Questions*) — authored via modelkit. */
    private static BoundModel survey() {
        return BoundModel.ofJson(DocumentModelDraft.named("survey").locale("en_US")
                .rootGroup("Survey", s -> s
                        .field("Title", FieldKind.STRING)
                        .group("Sections", sec -> sec.repeatable(10)
                                .field("SectionName", FieldKind.STRING)
                                .group("Questions", q -> q.repeatable(20)
                                        .field("QuestionText", FieldKind.STRING))))
                .build().toJson());
    }

    @Test
    void roundTripsBareAndSingleStarGroupScopes() {
        BoundModel m = order();
        // AllFieldsFilled(/Order/ShippingAddress) — bare (non-repeating) group
        Condition bare = m.field("/Order/CustomerName").filled()
                .and(Dsl.allFieldsFilled(m.flattenGroup("/Order/ShippingAddress")));
        // AtLeastOneFieldFilled(/Order/Items*) — single starred repeatable group
        Condition starred = m.field("/Order/CustomerName").filled()
                .and(Dsl.atLeastOneFieldFilled(m.flattenGroup("/Order/Items*")));

        var cases = new java.util.LinkedHashMap<String, Condition>();
        cases.put("/Order/BareScopeRule", bare);
        cases.put("/Order/StarScopeRule", starred);
        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "BareScopeRule", bare)).add(orderRule(m, "StarScopeRule", starred)));

        assertTrue(RENDER.render(bare).contains("AllFieldsFilled(/Order/ShippingAddress)"), RENDER.render(bare));
        assertTrue(RENDER.render(starred).contains("AtLeastOneFieldFilled(/Order/Items*)"), RENDER.render(starred));
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.read(fullName))),
                        () -> "group-scope round-trip mismatch for " + fullName));
    }

    @Test
    void roundTripsNestedStarGroupScopeWithoutCorruptingStars() {
        BoundModel m = survey();
        // AtLeastOneFieldFilled(/Survey/Sections*/Questions*) — BOTH stars must survive
        Condition nested = m.field("/Survey/Title").filled()
                .and(Dsl.atLeastOneFieldFilled(m.flattenGroup("/Survey/Sections*/Questions*")));

        Rule r = m.rule(m.group("/Survey")).name("NestedScopeRule").errorField(m.field("/Survey/Title"))
                .when(nested).code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));

        assertTrue(RENDER.render(nested).contains("AtLeastOneFieldFilled(/Survey/Sections*/Questions*)"),
                RENDER.render(nested));
        assertEquals(RENDER.render(nested), RENDER.render(converted(adapter.read("/Survey/NestedScopeRule"))));
    }

    @Test
    void roundTripsTheNotAndMoreThanOneQuantifiersOverAGroupScope() {
        BoundModel m = order();
        String scope = "/Order/ShippingAddress";   // a bare group — the fields inside it are the scope
        Condition notAll = m.field("/Order/CustomerName").filled()
                .and(Dsl.notAllFieldsFilled(m.flattenGroup(scope)));
        Condition notColl = m.field("/Order/CustomerName").filled()
                .and(Dsl.fieldsNotCollectivelyFilled(m.flattenGroup(scope)));
        Condition notOne = m.field("/Order/CustomerName").filled()
                .and(Dsl.notExactlyOneFieldFilled(m.flattenGroup(scope)));
        Condition moreOne = m.field("/Order/CustomerName").filled()
                .and(Dsl.moreThanOneFieldFilled(m.flattenGroup(scope)));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(orderRule(m, "NotAllScopeRule", notAll))
                .add(orderRule(m, "NotCollScopeRule", notColl))
                .add(orderRule(m, "NotOneScopeRule", notOne))
                .add(orderRule(m, "MoreOneScopeRule", moreOne)));

        assertTrue(RENDER.render(notAll).contains("NotAllFieldsFilled(/Order/ShippingAddress)"), RENDER.render(notAll));
        assertEquals(RENDER.render(notAll), RENDER.render(converted(adapter.read("/Order/NotAllScopeRule"))));
        assertEquals(RENDER.render(notColl), RENDER.render(converted(adapter.read("/Order/NotCollScopeRule"))));
        assertEquals(RENDER.render(notOne), RENDER.render(converted(adapter.read("/Order/NotOneScopeRule"))));
        assertEquals(RENDER.render(moreOne), RENDER.render(converted(adapter.read("/Order/MoreOneScopeRule"))));
    }

    @Test
    void roundTripsMoreThanOneFieldFilledOverAFieldList() {
        BoundModel m = order();
        // the field-list form (not a group scope): MoreThanOneFieldFilled(a, b)
        Condition c = Dsl.moreThanOneFieldFilled(m.field("/Order/CustomerName"), m.field("/Order/ProductCode"));
        KernelAdapter adapter = KernelAdapter.of(m.add(orderRule(m, "MoreOneFieldRule", c)));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/MoreOneFieldRule"))));
    }

    @Test
    void groupScopedFieldFillIsKernelValid() {
        BoundModel m = order();
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.atLeastOneFieldFilled(m.flattenGroup("/Order/Items*")));
        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "StarScopeRule", "../CustomerName", RENDER.render(c), "C", "ERROR"));
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
        try (InputStream in = GroupScopedFieldFillReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
