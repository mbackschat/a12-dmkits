package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * L2c (binding level, proposal §18.7 / D7) — the session-bound {@link GroupEditor}: refs are the currency.
 * One fluent idiom (the editor chains; the session-as-actor / ref-returning form is dropped), relative
 * navigation off a group to a pure ref for ANY child (existing or just-created), and an explicit downgrade
 * to the pure {@link GroupRef} to enter the immutable AST (so the session never leaks into a rule).
 */
class GroupEditorTest {

    @Test
    void navigatesToExistingChildrenAsPureTypedRefs() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());

        // navigate to a child GROUP off a parent editor → a pure GroupRef (repeatability read from the model)
        GroupRef addons = s.group("/Subscription").group("Addons");
        assertEquals("/Subscription/Addons", addons.path());
        assertTrue(addons.repeating());

        // relative navigation off a group editor → the SAME pure typed refs as m.num(...) / m.str(...)
        GroupEditor addonsEd = s.group("/Subscription/Addons");
        assertEquals("/Subscription/Addons", addonsEd.ref().path());
        assertEquals("/Subscription/Addons/MonthlyFee", addonsEd.num("MonthlyFee").field().path());
        assertEquals("/Subscription/Addons/Name", addonsEd.str("Name").field().path());
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
}
