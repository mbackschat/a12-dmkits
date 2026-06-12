package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * L2c — the rule-side editors {@link RuleEditor} / {@link ComputationEditor}: a held reference to an
 * existing rule/computation (its full name), so modify/remove don't re-type the path. Obtained from
 * {@link EditSession#rule(String)} / {@link EditSession#computation(String)}.
 */
class RuleComputationEditorTest {

    @Test
    void modifiesAndRemovesARuleByHeldReference() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor addons = s.group("/Subscription/Addons");
        addons.addField("Discount", FieldKind.NUMBER);
        NumberRef discount = addons.num("Discount");
        addons.addRule(r -> r.name("DiscountNonNeg").errorField(discount)
                .when(discount.filled().and(discount.lt(0)))
                .code("D_NEG").severity(Severity.ERROR)
                .message(Dsl.en("neg"), Dsl.de("neg")).build());
        s.validate();

        RuleEditor rule = s.rule("/Subscription/Addons/DiscountNonNeg");
        assertEquals("/Subscription/Addons/DiscountNonNeg", rule.name());

        // re-express the condition by held reference (no re-typing the full name)
        rule.modify(m -> {
            NumberRef d = m.num("/Subscription/Addons/Discount");
            return d.filled().and(d.lt(-5));
        });
        s.validate();
        assertTrue(s.toJson().contains("DiscountNonNeg"));

        // remove it by held reference
        s.rule("/Subscription/Addons/DiscountNonNeg").remove();
        s.validate();
        assertFalse(s.toJson().contains("DiscountNonNeg"));
    }

    @Test
    void removesAComputationByHeldReference() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = s.group("/Subscription/Billing");
        NumberRef baseFee = billing.num("BaseFee");
        NumberRef effectiveFee = billing.num("EffectiveFee");
        billing.addComputation(effectiveFee, c -> c.name("EffFeeComp")
                .calc(baseFee.value()).message(Dsl.en("x"), Dsl.de("x")).build());
        s.validate();

        ComputationEditor comp = s.computation("/Subscription/Billing/EffFeeComp");
        assertEquals("/Subscription/Billing/EffFeeComp", comp.name());

        comp.remove();
        s.validate();
        assertFalse(s.toJson().contains("EffFeeComp"));
    }

    @Test
    void modifiesAComputationsFormulaByHeldReference() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = s.group("/Subscription/Billing");
        NumberRef baseFee = billing.num("BaseFee");
        NumberRef effectiveFee = billing.num("EffectiveFee");
        billing.addComputation(effectiveFee, c -> c.name("EffFeeComp")
                .calc(baseFee.value()).message(Dsl.en("equals base"), Dsl.de("entspricht Basis")).build());
        s.validate();

        // re-express the formula via the TYPED builder, by held reference (mirrors RuleEditor.modify)
        s.computation("/Subscription/Billing/EffFeeComp")
                .modify(m -> m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                        .name("EffFeeComp")
                        .calc(NumberLiteral.of("7.77"))
                        .message(Dsl.en("flat fee"), Dsl.de("Pauschale")).build());
        s.validate();

        assertTrue(s.toJson().contains("7.77"));   // the re-expressed formula is in the model
    }
}
