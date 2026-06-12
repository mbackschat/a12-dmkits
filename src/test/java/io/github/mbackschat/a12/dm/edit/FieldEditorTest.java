package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

/**
 * L2c — the {@link FieldEditor}: a session-bound editor over one existing field, surfacing the field-
 * attribute edits (re-type, per-locale label, annotation) that {@code EditSession} otherwise lacked,
 * fluently and chaining, with an explicit {@link FieldEditor#ref()} downgrade. From {@link
 * EditSession#field(String)}.
 */
class FieldEditorTest {

    @Test
    void editsAnExistingFieldsTypeLabelAndAnnotation() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        FieldEditor monthlyFee = s.field("/Subscription/Addons/MonthlyFee");

        assertEquals("/Subscription/Addons/MonthlyFee", monthlyFee.ref().path());

        monthlyFee.setType(new FieldTypeSpec.NumberSpec(4, null, null))             // re-scale 2 -> 4
                .setLabel(Map.of("en_US", "Monthly Fee", "de_DE", "Monatsgebühr"))
                .setAnnotation("ui:widget", "currency");
        s.validate();

        // the re-type stuck — read the new scale back through group navigation
        assertEquals(4, s.group("/Subscription/Addons").num("MonthlyFee").scale());
    }

    @Test
    void setsAndClearsAFieldsRequiredness() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());

        s.field("/Subscription/Billing/BaseFee").setRequiredness(Requiredness.REQUIRED);
        s.validate();
        assertTrue(s.toJson().contains("absoluteOrRelativeToNextRepAncestor"));    // REQUIRED mode written

        s.field("/Subscription/Billing/BaseFee").setRequiredness(Requiredness.OPTIONAL);  // clears it
        s.validate();
        assertFalse(s.toJson().contains("absoluteOrRelativeToNextRepAncestor"));
    }
}
