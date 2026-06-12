package io.github.mbackschat.a12.dm.edit;

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
 * deferred — it ripples to every mount; see proposal §18.7.)
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
            if ("catalog".equals(reference)) {
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
