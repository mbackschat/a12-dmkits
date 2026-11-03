package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * modelkit M5 — a model can <b>include</b> another (a mount group carrying {@code includeConfig}, no inline
 * elements, plus its header {@code modelReferences} entry). The kernel expands the mount against a
 * resolver; the included fields then resolve under the mount's name-path (the included root-group
 * name is dropped — KERNEL-FINDINGS §10). Dogfood: both base and included models are authored by
 * modelkit. Own domain (a showroom including a pricelist).
 */
class IncludeTest {

    private static DocumentModelDraft pricelist() {
        return DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p
                        .numberField("ListPrice", n -> n.fractionDigits(2))
                        .field("Currency", FieldKind.STRING))
                .build();
    }

    private static DocumentModelDraft showroom() {
        return DocumentModelDraft.named("showroom").locale("en_US")
                .rootGroup("Showroom", s -> s
                        .field("Name", FieldKind.STRING)
                        .mount("Prices", "pricelist"))
                .build();
    }

    /** Resolves the "pricelist" reference to the modelkit-authored pricelist (read-only). */
    private static ModelResolver resolver() {
        String includedJson = pricelist().toJson();
        return reference -> {
            if ("pricelist".equals(reference)) {
                return includedJson;
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    @Test
    void anIncludedModelExpandsAndItsFieldsResolveUnderTheMount() {
        String json = showroom().toJson();
        try {
            ModelLoader.loadAndCheck(json, resolver());   // kernel gate: expand + checkConsistency (R3)
        } catch (Exception e) {
            throw new RuntimeException("emitted DM-JSON failed to expand/checkConsistency:\n" + json, e);
        }
        // the include is expanded by the kernel-free reader (decoupling Stage 2)
        ModelIndex index = ModelIndex.of(json, resolver());
        // base field
        assertEquals(ValueKind.STRING, index.kindOf("/Showroom/Name"));
        // included fields resolve at the mount name-path (included root group "Pricelist" dropped, §10)
        assertEquals(ValueKind.NUMBER, index.kindOf("/Showroom/Prices/ListPrice"));
        assertEquals(ValueKind.STRING, index.kindOf("/Showroom/Prices/Currency"));
    }

    @Test
    void emitsTheHeaderReferenceAndTheMountGroup() {
        String json = showroom().toJson();
        assertTrue(json.contains("\"purpose\" : \"include\""), json);
        assertTrue(json.matches("(?s).*\"includeConfig\" : \\{\\s*\"reference\" : \"pricelist\".*"), json);
        assertFalse(json.contains("alias"), "DM 29.4 writes no alias anywhere: " + json);
    }
}
