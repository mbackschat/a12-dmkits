package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * F10 read of a comparison on a <b>custom-typed field</b>. A custom field's value is string-shaped, so the
 * kernel compares it like any string (finding 46 / DG13, `m.custom(path)` → a {@code StringRef}). The
 * converter's {@code fieldValueComparison} switch had no {@code CUSTOM} case, so such a comparison read
 * Opaque ("does not yet handle comparison on a CUSTOM field"); it now converts via the string machinery.
 *
 * <p>Own domain: an order carrying a custom-typed product {@code Code}.
 */
class CustomFieldComparisonReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String formJson() {
        return DocumentModelDraft.named("form").locale("en_US")
                .rootGroup("Order", g -> g
                        .customField("Code", c -> c.type("Code").minLength(3).maxLength(5))
                        .stringField("Note", s -> { }))
                .build().toJson();
    }

    @Test
    void roundTripsACustomFieldComparison() {
        BoundModel m = BoundModel.ofJson(formJson());
        Condition c = m.custom("/Order/Code").filled().and(m.custom("/Order/Code").ne("abcd"));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "CodeRule", "/Order/Code", c)));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/CodeRule"))));
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Order")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
