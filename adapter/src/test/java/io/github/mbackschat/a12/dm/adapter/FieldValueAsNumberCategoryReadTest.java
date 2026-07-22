package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/** Typed authoring + read-back lock for the category-selected {@code FieldValueAsNumber} source. */
class FieldValueAsNumberCategoryReadTest {

    @Test
    void categoryNumericCoercionIsTypedAndRoundTrips() {
        String json = DocumentModelDraft.named("tariff").locale("en_US")
                .rootGroup("Tariff", group -> group.enumField("Band", band -> band
                        .value("BASIC").value("PLUS").value("PREMIUM")
                        .category("Multiplier", ".5", "1.5", "2.5")))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(json);

        // tag::category-number-coercion[]
        // A category projection converts the selected category token, not the enum's stored token.
        NumberExpr multiplier = Dsl.fieldValueAsNumber(
                m.enumOf("/Tariff/Band").category("Multiplier"));
        Condition premiumMultiplier = multiplier.ge("1.5");
        // end::category-number-coercion[]

        assertEquals("FieldValueAsNumber(/Tariff/Band -> Multiplier) >= 1.5",
                new DslRenderer().render(premiumMultiplier));
        Rule rule = m.rule(m.group("/Tariff")).name("PremiumMultiplier").errorField(m.field("/Tariff/Band"))
                .when(premiumMultiplier).code("PREMIUM_MULTIPLIER").severity(Severity.ERROR)
                .message(en("The selected band uses a premium multiplier.")).build();
        var read = KernelAdapter.of(m.add(rule)).read("/Tariff/PremiumMultiplier");
        var converted = assertInstanceOf(ReadResult.Converted.class, read);
        assertEquals(new DslRenderer().render(premiumMultiplier), new DslRenderer().render(converted.condition()));
    }
}
