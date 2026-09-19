package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * M10+ tail — richer field-type config (add a field with full {@link FieldTypeSpec} config; re-type an
 * existing field) and element metadata (per-locale labels, name/value annotations) over an existing
 * model. Each edit re-binds to the kernel.
 */
class ModelMetadataTest {

    private static ModelDocument subscription() {
        return ModelDocument.read(Fixtures.json("models/subscription.dm.json"));
    }

    @Test
    void addsAStringFieldWithBoundsAndPattern() {
        ModelDocument doc = subscription()
                .addField("/Subscription", "Coupon",
                        new FieldTypeSpec.StringSpec(3, 10, "[A-Z]{3,10}", "3–10 uppercase letters"))
                .validate();

        assertEquals("StringType", doc.fieldType("/Subscription/Coupon").orElseThrow());
        assertTrue(doc.toJson().contains("[A-Z]{3,10}"));
    }

    @Test
    void addsAnEnumField() {
        List<FieldTypeSpec.EnumValue> values = List.of(
                new FieldTypeSpec.EnumValue("LOW", Map.of("en_US", "Low", "de_DE", "Niedrig")),
                new FieldTypeSpec.EnumValue("HIGH", Map.of("en_US", "High", "de_DE", "Hoch")));
        ModelDocument doc = subscription()
                .addField("/Subscription", "RiskBand", new FieldTypeSpec.EnumSpec(values, List.of()))
                .validate();

        assertEquals("EnumerationType", doc.fieldType("/Subscription/RiskBand").orElseThrow());
    }

    @Test
    void reTypesAnExistingField() {
        // AutoRenew is a BooleanType in the fixture; re-type it to a ConfirmType
        ModelDocument doc = subscription()
                .setFieldType("/Subscription/AutoRenew", new FieldTypeSpec.ConfirmSpec(null, null))
                .validate();

        assertEquals("ConfirmType", doc.fieldType("/Subscription/AutoRenew").orElseThrow());
    }

    @Test
    void setsAPerLocaleLabel() {
        ModelDocument doc = subscription()
                .setLabel("/Subscription/Tier", Map.of("en_US", "Plan tier", "de_DE", "Tarifstufe"))
                .validate();

        assertTrue(doc.toJson().contains("Plan tier"));
        assertTrue(doc.toJson().contains("Tarifstufe"));
    }

    @Test
    void setsAnAnnotationOnAField() {
        ModelDocument doc = subscription()
                .setAnnotation("/Subscription/Tier", "ui:widget", "dropdown")
                .validate();

        assertTrue(doc.toJson().contains("ui:widget"));
        assertTrue(doc.toJson().contains("dropdown"));
    }

    @Test
    void editingMetadataOfAMissingElementFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> subscription().setLabel("/Subscription/Nope", Map.of("en_US", "x", "de_DE", "y")));
        assertThrows(IllegalArgumentException.class,
                () -> subscription().setFieldType("/Subscription/Nope", new FieldTypeSpec.ConfirmSpec(null, null)));
    }
}
