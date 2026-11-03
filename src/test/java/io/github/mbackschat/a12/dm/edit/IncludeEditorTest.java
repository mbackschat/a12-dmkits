package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;

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

    @Test
    void mountsAgainAndRemovesAnIncludeByHeldReference() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson(), resolver());
        s.mount("/Subscription", "Catalog", "catalog");   // declare + first mount
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
    void rePointsEveryMountAtADifferentModelAndHandsBackItsEditor() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson(), resolver());
        s.mount("/Subscription", "Catalog", "catalog");
        s.validate();

        // the mount follows the include: DM 29.4 names the included model on the mount itself (KF651)
        IncludeEditor repointed = s.include("catalog").setReference("catalog-v2");
        s.validate();   // resolver() resolves "catalog-v2" too

        ModelDocument doc = ModelDocument.read(s.toJson());
        assertEquals("catalog-v2", repointed.reference());
        assertEquals(List.of(new ModelDocument.Include("catalog-v2")), doc.includes());
        assertEquals(List.of("/Subscription/Catalog"), doc.mountsOfInclude("catalog-v2"));
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
            if (reference.startsWith("catalog")) {   // resolves "catalog" and "catalog-v2"
                return catalogJson;
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }
}
