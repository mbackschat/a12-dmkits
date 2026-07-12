package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * M7 — read an existing document model's structure and round-trip it losslessly. {@link ModelDocument}
 * reads the <em>authored source tree</em> (Jackson), so includes/typedefs/unknown elements are
 * preserved verbatim — the foundation for surgical structure editing (M8). The round-trip rides the
 * generic Jackson tree, not the kernel's typed mapper (which omits empty/default fields, KERNEL-FINDINGS),
 * so read → write-back is lossless modulo pretty-printing.
 */
class ModelDocumentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode tree(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void roundTripLosesNoStructure() {
        String json = Fixtures.json("models/subscription.dm.json");
        String out = ModelDocument.read(json).toJson();
        // tree-equal: every field/value preserved (no drop, no default-injection, no change)
        assertEquals(tree(json), tree(out), "read → write-back changed the model tree");
    }

    @Test
    void roundTripIsIdempotent() {
        String json = Fixtures.json("models/subscription.dm.json");
        String once = ModelDocument.read(json).toJson();
        String twice = ModelDocument.read(once).toJson();
        assertEquals(once, twice, "round-trip is not stable (text drifts on re-read)");
    }

    @Test
    void roundTripStaysKernelValid() throws Exception {
        String out = ModelDocument.read(Fixtures.json("models/subscription.dm.json")).toJson();
        // the written-back self-contained model still loads + checks-consistent through the real kernel
        ModelLoader.loadSelfContained(out);
    }

    @Test
    void enumeratesTheModelHeaderAndConfig() {
        ModelDocument doc = ModelDocument.read(Fixtures.json("models/subscription.dm.json"));
        assertEquals("subscription", doc.id());
        assertEquals("28.4.0", doc.modelVersion());
        assertEquals(List.of("en_US", "de_DE"), doc.locales());

        ModelDocument.DocConfig cfg = doc.config();
        assertEquals(".", cfg.decimalSeparator());
        assertEquals("Europe/Berlin", cfg.timeZone());
        assertEquals("en_US", cfg.conditionLanguage());
        assertTrue(cfg.fieldRefByShortNameAllowed());
    }

    @Test
    void enumeratesGroupsAndFieldsByNamePath() {
        ModelDocument doc = ModelDocument.read(Fixtures.json("models/subscription.dm.json"));

        assertTrue(doc.groupPaths().containsAll(
                List.of("/Subscription", "/Subscription/Addons", "/Subscription/Billing")));
        assertTrue(doc.fieldPaths().containsAll(
                List.of("/Subscription/Tier", "/Subscription/Addons/MonthlyFee",
                        "/Subscription/Billing/EffectiveFee")));

        // the field-type discriminator, read straight from the tree
        assertEquals("EnumerationType", doc.fieldType("/Subscription/Tier").orElseThrow());
        assertEquals("NumberType", doc.fieldType("/Subscription/Addons/MonthlyFee").orElseThrow());

        // fieldKind reports the clean ValueKind name (the user-facing vocabulary), not the kernel discriminator
        assertEquals("ENUMERATION", doc.fieldKind("/Subscription/Tier").orElseThrow());
        assertEquals("NUMBER", doc.fieldKind("/Subscription/Addons/MonthlyFee").orElseThrow());
        assertEquals("STRING", doc.fieldKind("/Subscription/Addons/Name").orElseThrow());
        assertTrue(doc.fieldKind("/Subscription/NoSuchField").isEmpty());

        // repeatability off the Group node
        assertTrue(doc.isRepeating("/Subscription/Addons"));
        assertFalse(doc.isRepeating("/Subscription"));
        assertFalse(doc.isRepeating("/Subscription/Billing"));
    }

    @Test
    void enumeratesIncludesFromHeaderModelReferences() {
        // storefront includes the `catalog` model (not self-contained) — read is tree-only, so it
        // surfaces the authored include without needing to resolve it.
        ModelDocument doc = ModelDocument.read(Fixtures.json("models/storefront.dm.json"));
        List<ModelDocument.Include> includes = doc.includes();
        assertEquals(1, includes.size());
        assertEquals("catalog", includes.get(0).alias());
        assertEquals("catalog", includes.get(0).reference());
    }

    @Test
    void enumeratesTypeDefinitionsRoundTrippedFromModelkitsOwnAuthoring() {
        // dogfood: author a model with a typedef via the build-from-scratch builder, then read it back.
        String json = DocumentModelDraft.named("widget")
                .locale("en_US")
                .typeDef("ShortText", t -> t.string(s -> s.maxLength(40)))
                .rootGroup("Widget", g -> g.typedefField("Code", "ShortText"))
                .build()
                .toJson();

        ModelDocument doc = ModelDocument.read(json);
        assertEquals(List.of("ShortText"), doc.typeDefinitionIds());
        assertEquals("TypeDefType", doc.fieldType("/Widget/Code").orElseThrow());

        // a typedef-typed field resolves to its underlying kind (STRING), matching what `model describe`
        // reports — the kernel `TypeDefType` discriminator never surfaces; the typedef itself reads STRING too
        assertEquals("STRING", doc.fieldKind("/Widget/Code").orElseThrow());
        assertEquals("STRING", doc.typeDefinitionKind("ShortText").orElseThrow());
    }
}
