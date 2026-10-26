package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConfirmRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * L2c (binding level, PROJECT-DESIGN §18.7 / D7) — the session-bound {@link GroupEditor}: refs are the currency.
 * One fluent idiom (the editor chains; the session-as-actor / ref-returning form is dropped), relative
 * navigation off a group to a pure ref for ANY child (existing or just-created), and an explicit downgrade
 * to the pure {@link GroupRef} to enter the immutable AST (so the session never leaks into a rule).
 */
class GroupEditorTest {

    @Test
    void perTypeFieldBuilderConfiguresAFieldWhenEditing() {
        // DG1 — the from-scratch per-type fluent builders are available on the EDIT surface, so editing
        // reads like authoring (no dropping to a hand-built FieldTypeSpec for a configured field).
        EditSession s = EditSession.read(Fixtures.orderJson());

        // tag::editor-number-field[]
        s.group("/Order").numberField("Surcharge", n -> n.fractionDigits(2).min("0"));   // configured field, mid-edit
        // end::editor-number-field[]

        assertEquals(2, s.boundModel().fieldAt("/Order/Surcharge").orElseThrow().scale(),
                "the per-type builder's scale config flows through the edit path");
        assertDoesNotThrow(s::validate, "the kernel accepts the configured field");
    }

    @Test
    void navigatesToExistingChildrenAsPureTypedRefs() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());

        // navigate to a child GROUP off a parent editor → a pure GroupRef (repeatability read from the model)
        GroupRef addons = s.group("/Subscription").group("Addons");
        assertEquals("/Subscription/Addons", addons.path().value());
        assertTrue(addons.repeating());

        // relative navigation off a group editor → the SAME pure typed refs as m.num(...) / m.str(...)
        GroupEditor addonsEd = s.group("/Subscription/Addons");
        assertEquals("/Subscription/Addons", addonsEd.ref().path().value());
        assertEquals("/Subscription/Addons/MonthlyFee", addonsEd.num("MonthlyFee").field().path().value());
        assertEquals("/Subscription/Addons/Name", addonsEd.str("Name").field().path().value());
    }

    @Test
    void flattensRelativeStarPaths() {
        // the star-aggregate peer of relative scalar nav (mirrors GroupInfo.flatten): a flatten relative to
        // the editor's group, base prepended, the relative portion carrying its own *(s). Equal to by-path.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        assertEquals(s.boundModel().flatten("/Subscription/Addons*/MonthlyFee"),
                s.group("/Subscription").flatten("Addons*/MonthlyFee"));
        assertEquals(s.boundModel().flattenGroup("/Subscription/Addons*"),
                s.group("/Subscription").flattenGroup("Addons*"));
    }

    @Test
    void buildsAStandaloneRuleScopedToTheGroupWithoutAddingIt() {
        // dogfood: a consumer authoring a Rule off an editor — the error field + condition are REFS, and the
        // kernel-relative error path ("../MonthlyFee") + the rendered condition are DERIVED, never hand-typed.
        // rule(...) BUILDS and returns (the read peer of addRule, which builds-and-adds) — the session is untouched.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor addons = s.group("/Subscription/Addons");
        NumberRef fee = addons.num("MonthlyFee");

        Rule r = addons.rule(b -> b.name("FeeCap").code("FEE_CAP")
                .errorField(fee).when(fee.gt(1000))
                .severity(Severity.ERROR).message(Dsl.en("fee too high"), Dsl.de("Gebühr zu hoch")).build());

        assertEquals("/Subscription/Addons", r.group().path().value());
        assertEquals("../MonthlyFee", r.errorEntityRelPath());     // derived from the ref, not "../MonthlyFee" typed
        assertEquals("[MonthlyFee] > 1000", r.render());           // group-relative canonical render
        assertFalse(s.toJson().contains("FeeCap"), "rule(...) builds and returns; it must NOT add to the session");
    }

    @Test
    void buildsAStandaloneComputationScopedToTheGroupWithoutAddingIt() {
        // dogfood: the computation peer — computed field is a ref, computedFieldRelPath() ("../EffectiveFee")
        // is derived; computation(...) builds-and-returns (peer of addComputation), leaving the session untouched.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = s.group("/Subscription/Billing");
        NumberRef eff = billing.num("EffectiveFee");

        Computation c = billing.computation(eff, b -> b.name("EffComp")
                .calc(NumberLiteral.of("9.99")).message(Dsl.en("computed"), Dsl.de("berechnet")).build());

        assertEquals("../EffectiveFee", c.computedFieldRelPath());
        assertFalse(s.toJson().contains("EffComp"), "computation(...) builds and returns; it must NOT add to the session");
    }

    @Test
    void createsAFieldThenReferencesItThroughTheEditor() {
        // the create→reference flow codegen (the dropped L2b) could never do — the field exists only at runtime.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor addons = s.group("/Subscription/Addons");

        // fluent structure edit — chains, mutating the session in place
        addons.addField("Discount", FieldKind.NUMBER);

        // a ref to the just-created field, by relative navigation off the same editor
        NumberRef discount = addons.num("Discount");

        // the editor downgrades to its GroupRef for the rule scope; the field ref is error-field + operand
        s.addRule(m -> m.rule(addons.ref())
                        .name("DiscountNotNegative")
                        .errorField(discount)
                        .when(discount.filled().and(discount.lt(0)))
                        .code("DISCOUNT_NEGATIVE").severity(Severity.ERROR)
                        .message(Dsl.en("Discount must not be negative."), Dsl.de("Rabatt darf nicht negativ sein."))
                        .build())
                .validate();

        assertTrue(ModelDocument.read(s.toJson()).fieldPaths().contains("/Subscription/Addons/Discount"));
    }

    @Test
    void chainsMultipleStructureEditsAtTheSameGroup() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());

        s.group("/Subscription/Addons")
                .addField("Discount", FieldKind.NUMBER)
                .addField("Note", FieldKind.STRING)
                .addField("Active", FieldKind.BOOLEAN);
        s.validate();

        ModelDocument result = ModelDocument.read(s.toJson());
        assertTrue(result.fieldPaths().contains("/Subscription/Addons/Discount"));
        assertTrue(result.fieldPaths().contains("/Subscription/Addons/Note"));
        assertTrue(result.fieldPaths().contains("/Subscription/Addons/Active"));
    }

    @Test
    void buildsNestedGroupsViaTheBuilderLambda() {
        // mirrors the from-scratch DocumentModelDraft builder: each new group is configured in its lambda,
        // so the model is never bound while a group sits empty (binding runs checkConsistency).
        EditSession s = EditSession.read(Fixtures.subscriptionJson());

        s.group("/Subscription")
                .addGroup("Shipping", g -> g.addField("Carrier", FieldKind.STRING))
                .addGroup("Items", items -> items
                        .addField("Sku", FieldKind.STRING)
                        .addField("Qty", FieldKind.NUMBER))
                .addRepeatableGroup("History", 10, h -> h.addField("ChangedOn", FieldKind.DATE));
        s.validate();

        ModelDocument result = ModelDocument.read(s.toJson());
        assertTrue(result.fieldPaths().contains("/Subscription/Shipping/Carrier"));
        assertTrue(result.fieldPaths().contains("/Subscription/Items/Sku"));
        assertTrue(result.fieldPaths().contains("/Subscription/Items/Qty"));
        assertTrue(result.isRepeating("/Subscription/History"));
        assertTrue(result.fieldPaths().contains("/Subscription/History/ChangedOn"));
    }

    @Test
    void addsRichlyTypedFieldsViaFieldTypeSpec() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = s.group("/Subscription/Billing");

        billing.addField("Discount", new FieldTypeSpec.NumberSpec(2, null, null))   // scale-2 number
                .addField("Coupon", new FieldTypeSpec.StringSpec(null, 20, null, null));  // bounded string
        s.validate();

        assertEquals(2, billing.num("Discount").scale());          // the scale stuck (read back via navigation)
        assertTrue(ModelDocument.read(s.toJson()).fieldPaths().contains("/Subscription/Billing/Coupon"));
    }

    @Test
    void declaresARichTypeDefinitionAndUsesItViaTheEditor() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());

        // a reusable scale-2 "Money" type-def — richer than a bare FieldKind (which carries no scale)
        s.addTypeDefinition("Money", new FieldTypeSpec.NumberSpec(2, null, null));

        // use it on a new field via the group editor
        s.group("/Subscription/Billing").addTypedefField("Surcharge", "Money");
        s.validate();

        assertEquals(2, s.group("/Subscription/Billing").num("Surcharge").scale());   // typedef scale flows through
        assertTrue(ModelDocument.read(s.toJson()).typeDefinitionIds().contains("Money"));
    }

    @Test
    void navigatesEveryFieldKindToATypedRef() {
        // relative navigation covers the whole typed-ref family, mirroring BoundModel's accessors.
        EditSession s = EditSession.read(Fixtures.orderJson());
        GroupEditor order = s.group("/Order");

        assertEquals("/Order/ExpressShipping", order.bool("ExpressShipping").field().path().value());
        assertEquals("/Order/Priority", order.enumOf("Priority").field().path().value());
        assertEquals("/Order/OrderDate", order.date("OrderDate").field().path().value());
        assertEquals("/Order/PickupTime", order.time("PickupTime").field().path().value());
        assertEquals("/Order/ScheduledAt", order.dateTime("ScheduledAt").field().path().value());
        assertEquals("/Order/ExpressShipping", order.field("ExpressShipping").path().value()); // kind-agnostic

        // confirm fields live in the subscription fixture
        ConfirmRef terms = EditSession.read(Fixtures.subscriptionJson())
                .group("/Subscription").confirm("TermsAccepted");
        assertEquals("/Subscription/TermsAccepted", terms.field().path().value());
    }

    @Test
    void authorsRulesAndComputationsScopedToTheGroup() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = s.group("/Subscription/Billing");
        NumberRef baseFee = billing.num("BaseFee");
        NumberRef effectiveFee = billing.num("EffectiveFee");

        // editor-scoped rule authoring — the builder is pre-scoped to /Subscription/Billing (no m.rule(group))
        billing.addRule(r -> r.name("BaseFeeNonNegative")
                .errorField(baseFee)
                .when(baseFee.filled().and(baseFee.lt(0)))
                .code("BASE_FEE_NEGATIVE").severity(Severity.ERROR)
                .message(Dsl.en("Base fee must not be negative."), Dsl.de("Basisgebühr darf nicht negativ sein."))
                .build());

        // editor-scoped computation authoring — the builder is pre-scoped to the computed field
        billing.addComputation(effectiveFee, c -> c.name("EffectiveFeeComp")
                .calc(baseFee.value())
                .message(Dsl.en("EffectiveFee equals BaseFee."), Dsl.de("Die effektive Gebühr entspricht der Basisgebühr."))
                .build());

        s.validate();
        String json = s.toJson();
        assertTrue(json.contains("BaseFeeNonNegative"));
        assertTrue(json.contains("EffectiveFeeComp"));
    }

    @Test
    void acceptsTheEditorDirectlyAsRuleScope() {
        // the aspirational ergonomic: pass the editor straight into m.rule(...) — it downgrades to its
        // GroupRef internally (the AST still stores only the pure ref). No rulekit → dm.edit dependency:
        // both GroupRef and GroupEditor satisfy the rulekit GroupReference type.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor addons = s.group("/Subscription/Addons");
        addons.addField("Discount", FieldKind.NUMBER);
        NumberRef discount = addons.num("Discount");

        s.addRule(m -> m.rule(addons)                          // the EDITOR as scope, not addons.ref()
                        .name("DiscountNotNegative")
                        .errorField(discount)
                        .when(discount.filled().and(discount.lt(0)))
                        .code("DISCOUNT_NEGATIVE").severity(Severity.ERROR)
                        .message(Dsl.en("Discount must not be negative."), Dsl.de("Rabatt darf nicht negativ sein."))
                        .build())
                .validate();

        assertTrue(ModelDocument.read(s.toJson()).fieldPaths().contains("/Subscription/Addons/Discount"));
    }

    @Test
    void mixesAnEditorRefAndAPathLookupInOneRule() {
        // a developer blends the two ref styles as fits: the editor for the field just added,
        // a path lookup for an existing field — both are the same pure ref, used in one condition.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor billing = s.group("/Subscription/Billing");

        billing.addField("Surcharge", FieldKind.NUMBER);              // a NEW field, via the editor
        NumberRef surcharge = billing.num("Surcharge");               // editor relative ref → the just-added field

        s.addRule(m -> {
            NumberRef baseFee = m.num("/Subscription/Billing/BaseFee");   // path lookup → an existing field
            return m.rule(billing.ref()).name("SurchargeUnderBase")
                    .errorField(surcharge)
                    .when(surcharge.filled().and(surcharge.gt(baseFee)))  // true on the VIOLATION (surcharge exceeds base)
                    .code("SURCHARGE_OVER_BASE").severity(Severity.ERROR)
                    .message(Dsl.en("Surcharge must not exceed the base fee."),
                            Dsl.de("Zuschlag darf die Basisgebühr nicht übersteigen."))
                    .build();
        });
        s.validate();

        assertTrue(s.toJson().contains("SurchargeUnderBase"));
    }

    @Test
    void composesDocumentPartBuildersWithoutNewMachinery() {
        // §5c.1: reusable document-part builders are just plain functions (GroupEditor, params) -> refs.
        // They compose, reuse under different names/groups, and every result passes the one validate() gate
        // — nothing beyond the editor + flowing refs is needed.
        EditSession s = EditSession.read(Fixtures.subscriptionJson());

        NumberRef surcharge = money(s.group("/Subscription/Billing"), "Surcharge");  // component #1
        money(s.group("/Subscription/Addons"), "Tip");                               // reused under another name
        GroupRef shipping = address(s.group("/Subscription"), "ShippingAddress");     // reusable substructure

        s.validate();

        // the builders return real, usable handles (not strings) — the whole point of refs-as-currency
        assertEquals("/Subscription/Billing/Surcharge", surcharge.field().path().value());
        assertEquals("/Subscription/ShippingAddress", shipping.path().value());

        ModelDocument result = ModelDocument.read(s.toJson());
        assertTrue(result.fieldPaths().contains("/Subscription/Billing/Surcharge"));
        assertTrue(result.fieldPaths().contains("/Subscription/Addons/Tip"));
        assertTrue(result.groupPaths().contains("/Subscription/ShippingAddress"));
        assertTrue(result.fieldPaths().contains("/Subscription/ShippingAddress/Street"));
    }

    /** A configured scale-2 "money" field plus its non-negative rule; returns the ref for further wiring. */
    private static NumberRef money(GroupEditor g, String name) {
        g.addField(name, new FieldTypeSpec.NumberSpec(2, null, null));
        NumberRef f = g.num(name);
        g.addRule(r -> r.name(name + "NonNegative")
                .errorField(f)
                .when(f.filled().and(f.lt(0)))
                .code(name.toUpperCase() + "_NEGATIVE").severity(Severity.ERROR)
                .message(Dsl.en(name + " must not be negative."), Dsl.de(name + " darf nicht negativ sein."))
                .build());
        return f;
    }

    @Test
    void aKeyedGroupOperandIsReachedRelativelyOffAnEditor() {
        // The editor's relative firstFilledGroup had no keyed sibling, so a consumer selecting one row by its key
        // had to drop to an absolute path on the bound model.
        EditSession s = EditSession.read(DocumentModelDraft.named("policy-contacts").locale("en_US")
                .rootGroup("Policy", p -> p.group("Contacts", c -> c.repeatable(3)
                        .stringField("Phone", f -> { }).stringField("Kind", f -> { }).indexField("Kind")))
                .build().toJson());

        assertEquals(s.boundModel().firstFilledGroupFor("/Policy/Contacts", "HOME"),
                s.group("/Policy").firstFilledGroupFor("Contacts", "HOME"));
    }

    /** A reusable address substructure built under {@code name}; returns the group ref. */
    private static GroupRef address(GroupEditor parent, String name) {
        parent.addGroup(name, a -> a
                .addField("Street", FieldKind.STRING)
                .addField("City", FieldKind.STRING)
                .addField("PostalCode", FieldKind.STRING));
        return parent.group(name);
    }
}
