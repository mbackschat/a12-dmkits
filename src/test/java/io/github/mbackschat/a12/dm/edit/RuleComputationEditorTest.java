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
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;

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
    void reSeveritiesAndReMessagesARuleByHeldReference() {
        // FEATURE-GAPS: severity / message edits in place — no full replace, no re-typing the path.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor addons = s.group("/Subscription/Addons");
        addons.addField("Discount", FieldKind.NUMBER);
        NumberRef discount = addons.num("Discount");
        addons.addRule(r -> r.name("DiscountNonNeg").errorField(discount)
                .when(discount.filled().and(discount.lt(0)))
                .code("D_NEG").severity(Severity.ERROR)
                .message(Dsl.en("neg"), Dsl.de("neg")).build());
        s.validate();

        s.rule("/Subscription/Addons/DiscountNonNeg")
                .severity(Severity.WARNING)
                .messages(Dsl.en("Discount should not be negative."), Dsl.de("Rabatt sollte nicht negativ sein."));
        s.validate();   // the kernel still accepts the rule after the in-place severity + message edit

        String json = s.toJson();
        assertTrue(json.contains("\"severity\" : \"WARNING\""), () -> "the new severity is written: " + json);
        assertTrue(json.contains("Discount should not be negative."), () -> "the new message is written: " + json);
    }

    @Test
    void authorsCommentsThroughTheEditorOnBothARuleAndAComputation() {
        // the L2c editor carries the comment feature for free — GroupEditor.addRule/addComputation hand
        // the same Rule.Builder/Computation.Builder, so .comment(...) flows straight through.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor addons = s.group("/Subscription/Addons");
        addons.addField("Discount", FieldKind.NUMBER);
        NumberRef discount = addons.num("Discount");
        addons.addRule(r -> r.name("DiscountNonNeg").errorField(discount)
                .when(discount.filled().and(discount.lt(0)))
                .comment("discounts may not be negative")
                .code("D_NEG").severity(Severity.ERROR)
                .message(Dsl.en("neg"), Dsl.de("neg")).build());
        s.validate();
        assertTrue(s.toJson().contains(";; discounts may not be negative"),
                "the editor-authored rule carries its comment");

        GroupEditor billing = s.group("/Subscription/Billing");
        NumberRef baseFee = billing.num("BaseFee");
        NumberRef effectiveFee = billing.num("EffectiveFee");
        billing.addComputation(effectiveFee, c -> c.name("EffFeeComp")
                .comment("effective fee equals the base fee")
                .calc(baseFee.value()).message(Dsl.en("x"), Dsl.de("x")).build());
        s.validate();
        assertTrue(s.toJson().contains(";; effective fee equals the base fee"),
                "the editor-authored computation carries its comment");
    }

    @Test
    void authorsAStringComputationThroughTheEditor() {
        // The editor's computation()/addComputation() accept the StringRef target BoundModel.computation always
        // did (a concatenation-computed STRING field), not only NumberRef — the compose peer returns the built
        // computation with its derived relPath, the add form lands it in the session.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor addons = s.group("/Subscription/Addons");
        addons.addField("Code", FieldKind.STRING);
        addons.addField("Label", FieldKind.STRING);
        StringRef code = addons.str("Code");
        StringRef label = addons.str("Label");

        Computation composed = addons.computation(label, c -> c.name("LabelComp")
                .calc(code.asString().plus("-X").expression())
                .message(Dsl.en("x"), Dsl.de("x")).build());
        assertEquals("../Label", composed.computedFieldRelPath(), "the compose peer derives the target relPath");

        addons.addComputation(label, c -> c.name("LabelComp")
                .calc(code.asString().plus("-X").expression())
                .message(Dsl.en("x"), Dsl.de("x")).build());
        s.validate();   // the kernel accepts the string-computed target
        assertTrue(s.toJson().contains("LabelComp"), "the editor-authored string computation is written");
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
    void preservesAComputationsLeadingCommentOnModify() {
        // #37 — the computation peer of #31: a comment authored on the operation survives a re-express
        // that doesn't restate it (replaceComputation rebuilds the element, so the old slot comment is carried).
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = s.group("/Subscription/Billing");
        NumberRef baseFee = billing.num("BaseFee");
        NumberRef effectiveFee = billing.num("EffectiveFee");
        billing.addComputation(effectiveFee, c -> c.name("EffFeeComp")
                .comment("effective fee equals the base fee")
                .calc(baseFee.value()).message(Dsl.en("x"), Dsl.de("x")).build());
        s.validate();
        assertTrue(s.toJson().contains(";; effective fee equals the base fee"), "authored on the operation");

        // re-express the formula WITHOUT restating the comment — it rides along
        s.computation("/Subscription/Billing/EffFeeComp")
                .modify(m -> m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                        .name("EffFeeComp")
                        .calc(NumberLiteral.of("9.99"))
                        .message(Dsl.en("flat"), Dsl.de("pauschal")).build());
        s.validate();

        assertTrue(s.toJson().contains("9.99"), "the new formula is applied");
        assertTrue(s.toJson().contains(";; effective fee equals the base fee"),
                "the leading comment survives the computation modify (preserve-by-default)");
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
