package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * M10+ — the structure-coverage tail beyond fields/groups: editing <b>doc config</b> (the comma-vs-point
 * concern), <b>type definitions</b>, and <b>includes</b> on an existing model. Each edit re-binds to the
 * real kernel (self-contained, or with a resolver for the included model).
 */
class ModelStructureCoverageTest {

    private static ModelDocument subscription() {
        return ModelDocument.read(Fixtures.json("models/subscription.dm.json"));
    }

    @Test
    void editsDocConfigDecimalSeparatorAndTimeZone() {
        ModelDocument doc = subscription()
                .setDecimalSeparator(",")
                .setTimeZone("UTC")
                .validate();

        assertEquals(",", doc.config().decimalSeparator());
        assertEquals("UTC", doc.config().timeZone());
    }

    @Test
    void addsATypeDefinitionAndAFieldUsingIt() {
        ModelDocument doc = subscription()
                .addTypeDefinition("Money", FieldKind.NUMBER)
                .addTypedefField("/Subscription", "Credit", "Money")
                .validate();

        assertTrue(doc.typeDefinitionIds().contains("Money"));
        assertEquals("TypeDefType", doc.fieldType("/Subscription/Credit").orElseThrow());
    }

    @Test
    void addsAnIncludeAndMountsItValidatedAgainstTheReferencedModel() {
        ModelDocument doc = subscription()
                .mount("/Subscription", "Catalog", "catalog")
                .validate(resolver());

        assertEquals(1, doc.includes().size());
        assertEquals("catalog", doc.includes().get(0).reference());
        assertTrue(doc.groupPaths().contains("/Subscription/Catalog"));
    }

    @Test
    void removesATypeDefinition() {
        ModelDocument doc = subscription().addTypeDefinition("Money", FieldKind.NUMBER).validate();
        assertTrue(doc.typeDefinitionIds().contains("Money"));
        doc.removeTypeDefinition("Money").validate();
        assertFalse(doc.typeDefinitionIds().contains("Money"));
    }

    @Test
    void removesAnIncludeAndItsMount() {
        ModelDocument doc = subscription()
                .mount("/Subscription", "Catalog", "catalog")
                .validate(resolver());
        assertTrue(doc.groupPaths().contains("/Subscription/Catalog"));

        doc.removeInclude("catalog").validate();   // self-contained again — the reference + mount are gone
        assertEquals(0, doc.includes().size());
        assertFalse(doc.groupPaths().contains("/Subscription/Catalog"));
    }

    /** A tiny included model authored with modelkit (same locales as subscription). */
    private static String catalogModel() {
        return DocumentModelDraft.named("catalog").locale("en_US").locale("de_DE")
                .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING))
                .build()
                .toJson();
    }

    private static ModelResolver resolver() {
        String catalogJson = catalogModel();
        return reference -> {
            if ("catalog".equals(reference)) {
                return catalogJson;
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }
}
