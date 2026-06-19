package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * L2c — the {@link IncludeEditor}: a held reference to a declared include (by alias), so mounting it again
 * or removing it doesn't re-type the alias. From {@link EditSession#include(String)}. (Re-alias is
 * deferred — it ripples to every mount; see PROJECT-DESIGN §18.7.)
 */
class IncludeEditorTest {

    private static final IDocumentModelSerializer SERIALIZER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    @Test
    void mountsAgainAndRemovesAnIncludeByHeldReference() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson(), resolver());
        s.addInclude("catalog", "catalog", "/Subscription", "Catalog");   // declare + first mount
        s.validate();
        assertTrue(ModelDocument.read(s.toJson()).groupPaths().contains("/Subscription/Catalog"));

        // mount the SAME include a second time, by held reference
        s.include("catalog").mount("/Subscription", "Catalog2");
        s.validate();
        assertTrue(ModelDocument.read(s.toJson()).groupPaths().contains("/Subscription/Catalog2"));

        // remove by held reference — drops the include and all its mounts
        s.include("catalog").remove();
        s.validate();
        assertFalse(ModelDocument.read(s.toJson()).groupPaths().contains("/Subscription/Catalog"));
    }

    @Test
    void rePointsAnIncludeAtADifferentModel() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson(), resolver());
        s.addInclude("catalog", "catalog", "/Subscription", "Catalog");
        s.validate();

        // re-point at a different referenced model — alias unchanged, so the mounts are untouched
        s.include("catalog").setReference("catalog-v2");
        s.validate();   // resolver() resolves "catalog-v2" too

        assertEquals("catalog-v2", ModelDocument.read(s.toJson()).includes().get(0).reference());
    }

    @Test
    void renamesAnIncludeAlias() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson(), resolver());
        s.addInclude("catalog", "catalog", "/Subscription", "Catalog");
        s.validate();

        // the header alias AND the mount's modelAlias move together; validate() passing proves nothing dangles
        s.include("catalog").rename("pricelist");
        s.validate();

        ModelDocument doc = ModelDocument.read(s.toJson());
        assertEquals("pricelist", doc.includes().get(0).alias());
        assertTrue(doc.groupPaths().contains("/Subscription/Catalog"));   // mount path (name-keyed) is unchanged
    }

    /** A tiny included model authored with modelkit (same locales as subscription). */
    private static String catalogModel() {
        return DocumentModelDraft.named("catalog").locale("en_US").locale("de_DE")
                .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING))
                .build()
                .toJson();
    }

    private static IDocumentModelReferenceResolver resolver() {
        String catalogJson = catalogModel();
        return reference -> {
            if (reference.startsWith("catalog")) {   // resolves "catalog" and "catalog-v2"
                try {
                    return SERIALIZER.deserialize(new StringReader(catalogJson));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }
}
