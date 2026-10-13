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
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;

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
                .calc(code.plus("-X"))
                .message(Dsl.en("x"), Dsl.de("x")).build());
        assertEquals("../Label", composed.computedFieldRelPath(), "the compose peer derives the target relPath");

        addons.addComputation(label, c -> c.name("LabelComp")
                .calc(code.plus("-X"))
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
    void preservesAComputationsElementMetadataOnModify() {
        // F2 — replaceComputation is remove-old + add-rebuilt, and the rebuilt node only carries the slots
        // computationNode writes: element-level metadata (annotations, internalDescription) was silently lost.
        // The rule-modify sibling mutates in place and never loses it — the fix brings computation to parity.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = s.group("/Subscription/Billing");
        NumberRef baseFee = billing.num("BaseFee");
        NumberRef effectiveFee = billing.num("EffectiveFee");
        billing.addComputation(effectiveFee, c -> c.name("EffFeeComp")
                .calc(baseFee.value()).message(Dsl.en("equals base"), Dsl.de("entspricht Basis")).build());
        s.setInternalDescription("/Subscription/Billing/EffFeeComp", "en", "Legacy pricing note — do not drop.");
        s.setAnnotation("/Subscription/Billing/EffFeeComp", "owner", "billing-team");
        s.validate();

        // re-express the formula via the typed builder — the metadata must ride along
        s.computation("/Subscription/Billing/EffFeeComp")
                .modify(m -> m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                        .name("EffFeeComp")
                        .calc(NumberLiteral.of("9.99"))
                        .message(Dsl.en("flat"), Dsl.de("pauschal")).build());
        s.validate();

        String json = s.toJson();
        assertTrue(json.contains("9.99"), "the new formula is applied");
        assertTrue(json.contains("Legacy pricing note — do not drop."),
                () -> "the internalDescription survives the computation modify: " + json);
        assertTrue(json.contains("billing-team"),
                () -> "the annotation survives the computation modify: " + json);
    }

    /**
     * The same contract one level deeper — a Computation BODY member the rebuild does not write.
     *
     * <p>The element-level fix above carried `annotations`/`internalDescription` across because
     * {@code computationNode} reconstructs only `type`/`id`/`name`/`Computation`. The body it reconstructs is
     * partial in the same way: A12 declares {@code variantFilter} on {@code md.model.a12internal.Computation}
     * beside the members the writer emits, so a modify used to drop it — silent SEMANTIC loss, since the
     * computation would join every variant rather than the ones it was filtered to.
     *
     * <p>`variantFilter` has no typed surface, so the probe injects it and re-reads. {@code ALL_EXCEPT} keeps
     * the computation in the default variant, which the session validates.
     */
    @Test
    void preservesAComputationsBodyMembersTheRebuildDoesNotWrite() {
        String stored = injectIntoComputationBody(roundedFee(null),
                "\"variantFilter\" : {\"filterType\" : \"ALL_EXCEPT\", \"variants\" : [\"legacy\"]}");
        assertTrue(stored.contains("ALL_EXCEPT"), "the probe must actually carry the member it is about");

        String json = reExpressed(stored, null);
        assertTrue(json.contains("9.99"), "the new formula is applied");
        assertTrue(json.contains("\"variantFilter\"") && json.contains("ALL_EXCEPT"),
                () -> "the variantFilter survives the computation modify: " + json);
    }

    /**
     * A member the typed builder DOES express is the re-expression's to state, as {@code errorCodesToSuppress}
     * and {@code commonPrecondition} are: carrying the old value over would make removing it impossible. So a
     * restated mode survives, a changed one replaces it, and an unstated one is {@code Exact}, which A12 never
     * stores.
     */
    @Test
    void aTypedReExpressionIsAuthoritativeForTheRoundingMode() {
        String stored = roundedFee(RoundingMode.ROUND_UP);
        assertTrue(stored.contains("\"roundingMode\" : \"RoundUp\""), () -> "the probe carries the mode: " + stored);

        assertTrue(reExpressed(stored, RoundingMode.ROUND_UP).contains("\"roundingMode\" : \"RoundUp\""),
                "a restated mode survives");
        String down = reExpressed(stored, RoundingMode.ROUND_DOWN);
        assertTrue(down.contains("\"roundingMode\" : \"RoundDown\"") && !down.contains("RoundUp"),
                () -> "a changed mode replaces the old one: " + down);
        String exact = reExpressed(stored, null);
        assertFalse(exact.contains("roundingMode"), () -> "an unstated mode is Exact, never stored: " + exact);
    }

    /** The subscription model with a banded {@code RoundedFee} computation, rounded by [mode] when not null. */
    private static String roundedFee(RoundingMode mode) {
        EditSession authored = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = authored.group("/Subscription/Billing");
        NumberRef baseFee = billing.num("BaseFee");
        NumberRef effectiveFee = billing.num("EffectiveFee");
        billing.addComputation(effectiveFee, c -> c.name("RoundedFee")
                .calc(baseFee.value(), ToleranceBand.TR1).roundingMode(mode)
                .message(Dsl.en("rounded"), Dsl.de("gerundet")).build());
        authored.validate();
        return authored.toJson();
    }

    /** [stored] with [member] added to the {@code RoundedFee} body, as a model from elsewhere would carry it. */
    private static String injectIntoComputationBody(String stored, String member) {
        String anchor = "\"computedFieldRelPath\" : \"../EffectiveFee\"";
        assertTrue(stored.contains(anchor), "the probe's anchor must be where the writer puts it");
        return stored.replace(anchor, member + ", " + anchor);
    }

    /** [stored] after a typed re-expression of {@code RoundedFee} to a flat fee, rounded by [mode]. */
    private static String reExpressed(String stored, RoundingMode mode) {
        EditSession s = EditSession.read(stored);
        s.validate();
        s.computation("/Subscription/Billing/RoundedFee")
                .modify(m -> m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                        .name("RoundedFee")
                        .calc(NumberLiteral.of("9.99"), ToleranceBand.TR1).roundingMode(mode)
                        .message(Dsl.en("flat"), Dsl.de("pauschal")).build());
        s.validate();
        return s.toJson();
    }

    @Test
    void editsAComputationsElementMetadataByHeldReference() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = s.group("/Subscription/Billing");
        NumberRef baseFee = billing.num("BaseFee");
        NumberRef effectiveFee = billing.num("EffectiveFee");
        billing.addComputation(effectiveFee, c -> c.name("EffFeeComp")
                .calc(baseFee.value()).message(Dsl.en("equals base"), Dsl.de("entspricht Basis")).build());

        s.computation("/Subscription/Billing/EffFeeComp")
                .setAnnotation("owner", "pricing-team")
                .setAnnotation("temporary", "remove-me")
                .removeAnnotation("temporary")
                .setInternalDescription("en_US", "The maintained pricing formula.")
                .setExternalDescription("en_US", "Copies the base fee into the effective fee.");
        s.validate();

        String json = s.toJson();
        assertTrue(json.contains("pricing-team"), () -> "the annotation is written: " + json);
        assertFalse(json.contains("remove-me"), () -> "the removed annotation is absent: " + json);
        assertTrue(json.contains("The maintained pricing formula."),
                () -> "the internal description is written: " + json);
        assertTrue(json.contains("Copies the base fee into the effective fee."),
                () -> "the external description is written: " + json);
    }

    @Test
    void preservesARulesElementMetadataOnModify() {
        // the in-place sibling — rule modify mutates the node, so metadata was never at risk; locked here so the
        // metadata-survives-modify contract covers BOTH the rule and computation modify paths.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor addons = s.group("/Subscription/Addons");
        addons.addField("Discount", FieldKind.NUMBER);
        NumberRef discount = addons.num("Discount");
        addons.addRule(r -> r.name("DiscountNonNeg").errorField(discount)
                .when(discount.filled().and(discount.lt(0)))
                .code("D_NEG").severity(Severity.ERROR)
                .message(Dsl.en("neg"), Dsl.de("neg")).build());
        s.setInternalDescription("/Subscription/Addons/DiscountNonNeg", "en", "Compliance note — keep me.");
        s.setAnnotation("/Subscription/Addons/DiscountNonNeg", "owner", "risk-team");
        s.validate();

        s.rule("/Subscription/Addons/DiscountNonNeg").modify(m -> {
            NumberRef d = m.num("/Subscription/Addons/Discount");
            return d.filled().and(d.lt(-5));
        });
        s.validate();

        String json = s.toJson();
        assertTrue(json.contains("Compliance note — keep me."),
                () -> "the internalDescription survives the rule modify: " + json);
        assertTrue(json.contains("risk-team"),
                () -> "the annotation survives the rule modify: " + json);
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
