package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;

/**
 * M8 — surgical add/modify/delete of fields and groups over the authored tree, re-bound to the real
 * kernel for validation (the two-tier constraint gate: cheap shape rules here, the full set delegated to
 * {@code checkConsistency} via {@link ModelDocument#validate()}). Edits mutate the tree in place (an edit
 * session); {@link ModelDocument#toJson()} writes the result, preserving everything untouched.
 */
class ModelEditTest {

    private static ModelDocument subscription() {
        return ModelDocument.read(Fixtures.json("models/subscription.dm.json"));
    }

    @Test
    void addsAFieldTheKernelAccepts() {
        ModelDocument doc = subscription()
                .addField("/Subscription", "Discount", FieldKind.NUMBER)
                .validate();

        assertTrue(doc.fieldPaths().contains("/Subscription/Discount"));
        assertEquals("NumberType", doc.fieldType("/Subscription/Discount").orElseThrow());

        // the edit survives a write → re-read round-trip
        assertTrue(ModelDocument.read(doc.toJson()).fieldPaths().contains("/Subscription/Discount"));
    }

    @Test
    void addsAGroupWithAField() {
        ModelDocument doc = subscription()
                .addGroup("/Subscription", "Notes")
                .addField("/Subscription/Notes", "Text", FieldKind.STRING)
                .validate();

        assertTrue(doc.groupPaths().contains("/Subscription/Notes"));
        assertTrue(doc.fieldPaths().contains("/Subscription/Notes/Text"));
        assertFalse(doc.isRepeating("/Subscription/Notes"));
    }

    @Test
    void addsARepeatableGroup() {
        ModelDocument doc = subscription()
                .addRepeatableGroup("/Subscription", "History", 10)
                .addField("/Subscription/History", "ChangedOn", FieldKind.DATE)
                .validate();

        assertTrue(doc.isRepeating("/Subscription/History"));
    }

    @Test
    void removesAField() {
        ModelDocument doc = subscription()
                .removeField("/Subscription/AutoRenew")
                .validate();

        assertFalse(doc.fieldPaths().contains("/Subscription/AutoRenew"));
    }

    @Test
    void removesAGroupAndAllItsFields() {
        ModelDocument doc = subscription()
                .removeGroup("/Subscription/Billing")
                .validate();

        assertFalse(doc.groupPaths().contains("/Subscription/Billing"));
        assertFalse(doc.fieldPaths().contains("/Subscription/Billing/EffectiveFee"));
    }

    @Test
    void makesAGroupRepeatable() {
        ModelDocument doc = subscription()
                .setRepeatable("/Subscription/Billing", 5)
                .validate();

        assertTrue(doc.isRepeating("/Subscription/Billing"));
    }

    @Test
    void editingAMissingGroupFailsFastBeforeTheKernel() {
        assertThrows(IllegalArgumentException.class,
                () -> subscription().addField("/Nope", "X", FieldKind.STRING));
        assertThrows(IllegalArgumentException.class,
                () -> subscription().removeField("/Subscription/DoesNotExist"));
    }

    @Test
    void theKernelGateRejectsAnInvalidEdit() {
        // a duplicate field (Tier already exists) is shape-legal but kernel-invalid — the tier-2 gate
        ModelDocument doc = subscription().addField("/Subscription", "Tier", FieldKind.STRING);
        assertThrows(ModelConsistencyException.class, doc::validate);
    }

    @Test
    void setsDocConfigShortNameAllowedAndBaseYear() {
        ModelDocument doc = subscription()
                .setFieldRefByShortNameAllowed(false)
                .setBaseYear(2030)
                .validate();

        assertFalse(doc.config().fieldRefByShortNameAllowed());
        assertTrue(doc.toJson().contains("2030"));   // baseYear written (string-valued; no getter on DocConfig)
    }
}
