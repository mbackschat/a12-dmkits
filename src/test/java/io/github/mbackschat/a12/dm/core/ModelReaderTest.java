package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;

/**
 * A1 — the kernel-free {@link ModelReader} (kernel-decoupling Stage 2). Proves the assembly layer kernel-free:
 * name-path indexing (F-IDX), include mounting (SINGLE_RG / MODEL_ROOT + exclude flags), imported-typedef
 * effective-type resolution, id normalization, and the interpreter-ready {@link ModelReader#toJson()} form.
 * The differential-vs-the-kernel bar is met separately by the parity net ({@code adapter.ModelIndexParityTest}).
 */
class ModelReaderTest {

    // ── name-path indexing over a self-contained model (F-IDX: index by names, not opaque ids) ────────────

    @Test
    void indexesByNamePathEvenWhenIdsAreOpaque() {
        ModelReader reader = ModelReader.expand(Fixtures.json("models/idmismatch.dm.json"),
                ReferenceResolvers.selfContained());
        assertTrue(reader.paths().contains("/Catalog"));
        assertTrue(reader.paths().contains("/Catalog/Sku"));
        assertTrue(reader.paths().contains("/Catalog/ListPrice"));
        assertEquals("StringType", fieldTypeName(reader, "/Catalog/Sku"));
        assertEquals("NumberType", fieldTypeName(reader, "/Catalog/ListPrice"));
    }

    @Test
    void normalizesElementIdsToTheirNamePath() {
        ModelReader reader = ModelReader.expand(Fixtures.json("models/idmismatch.dm.json"),
                ReferenceResolvers.selfContained());
        // opaque hash ids in the source become name-paths in the expanded form (interpreter-ready)
        assertEquals("/Catalog/ListPrice", reader.element("/Catalog/ListPrice").path("id").asText());
        assertEquals("/Catalog", reader.element("/Catalog").path("id").asText());
    }

    // ── imported type-definition resolution (effective types) ─────────────────────────────────────────────

    @Test
    void inlinesTypedefFieldsToTheirConcreteEffectiveType() {
        String json = DocumentModelDraft.named("policy").locale("en_US")
                .typeDef("Sku", t -> t.string(s -> s.maxLength(12)))
                .rootGroup("Policy", p -> p
                        .field("PolicyNo", FieldKind.STRING)
                        .typedefField("ProductSku", "Sku"))
                .build().toJson();
        ModelReader reader = ModelReader.expand(json, ReferenceResolvers.selfContained());
        JsonNode fieldType = reader.element("/Policy/ProductSku").path("Field").path("fieldType");
        assertEquals("StringType", fieldType.path("type").asText(), "the typedef resolves to its concrete kind");
        assertEquals(12, fieldType.path("StringType").path("maxLength").asInt(), "and carries its concrete config");
    }

    @Test
    void leavesADanglingTypedefReferenceUnresolved() {
        // a TypeDefType whose id has no matching definition stays a TypeDefType (kernel: getEffectiveType() empty
        // → the field falls back to its declared TypeDefType → ValueKind.UNKNOWN).
        String json = """
            { "header": { "id": "m", "modelType": "document", "modelVersion": "28.4.0",
                "locales": [{"code":"en_US"}], "modelReferences": [] },
              "content": { "modelInfo": {"name":"m"}, "modelRoot": { "rootGroups": [
                { "type":"Group", "id":"/G", "name":"G", "Group": { "repeatability":1, "elements": [
                  { "type":"Field", "id":"/G/F", "name":"F",
                    "Field": { "fieldType": { "type":"TypeDefType", "TypeDefType": { "typeDefinitionId":"Nope" } } } }
                ] } } ] } } }
            """;
        ModelReader reader = ModelReader.expand(json, ReferenceResolvers.selfContained());
        assertEquals("TypeDefType", fieldTypeName(reader, "/G/F"));
    }

    // ── SINGLE_RG include mounting (the committed F-IDX storefront/catalog fixtures) ──────────────────────

    @Test
    void mountsAnIncludedModelUnderTheModelAliasGroupNotItsRootGroupName() {
        ModelReader reader = ModelReader.expand(Fixtures.json("models/storefront.dm.json"), catalogResolver());
        // the included Catalog's CHILDREN land under the mount group /Storefront/Inventory —
        // the included model's own root-group name "Catalog" never appears (kernel expand semantics).
        assertTrue(reader.paths().contains("/Storefront/Inventory/Sku"), () -> "mounted fields: " + reader.paths());
        assertTrue(reader.paths().contains("/Storefront/Inventory/ListPrice"));
        assertFalse(reader.paths().stream().anyMatch(p -> p.contains("/Catalog")),
                () -> "the included root-group name must not appear in a path: " + reader.paths());
        assertEquals("NumberType", fieldTypeName(reader, "/Storefront/Inventory/ListPrice"));
        // and the expanded form is self-contained + name-path-keyed (interpreter-ready)
        assertEquals("/Storefront/Inventory/ListPrice",
                reader.element("/Storefront/Inventory/ListPrice").path("id").asText());
        assertFalse(reader.toJson().contains("modelAlias"), "the mount is folded, no vestigial modelAlias remains");
    }

    // ── MODEL_ROOT vs SINGLE_RG ───────────────────────────────────────────────────────────────────────────

    @Test
    void modelRootIncludeKeepsTheIncludedRootGroupAsASubgroup() {
        String rider = DocumentModelDraft.named("rider").locale("en_US")
                .rootGroup("Rider", r -> r.numberField("Amount", n -> n.fractionDigits(2)))
                .build().toJson();
        String policy = DocumentModelDraft.named("policy").locale("en_US")
                .include("rider", "rider")
                .rootGroup("Policy", p -> p
                        .field("PolicyNo", FieldKind.STRING)
                        .mount("Extras", "rider", MountOptions.single().asModelRoot()))
                .build().toJson();
        ModelReader reader = ModelReader.expand(policy, ref -> "rider".equals(ref) ? rider : null);
        // MODEL_ROOT preserves the included root group as a subgroup under the mount
        assertTrue(reader.paths().contains("/Policy/Extras/Rider"), () -> "paths: " + reader.paths());
        assertTrue(reader.paths().contains("/Policy/Extras/Rider/Amount"), () -> "paths: " + reader.paths());
    }

    // ── include exclude flags + the relativization signal (A4 guard) ─────────────────────────────────────

    @Test
    void carriesMountedIncludedRulesAndSignalsThemForRelativization() {
        ModelReader reader = ModelReader.expand(policyIncludingBenefits(false), benefitsResolver());
        assertTrue(reader.paths().contains("/Policy/Benefits/Coverage"), () -> "paths: " + reader.paths());
        // the mounted included rule is carried verbatim (its condition is not relativized) — signalled so a
        // correctness-critical consumer refuses rather than mis-evaluates (A4 / IG67).
        assertEquals(List.of("CoveragePositive"), reader.includedRulesNeedingRelativization());
    }

    @Test
    void excludeRulesDropsMountedIncludedRulesAndClearsTheRelativizationSignal() {
        ModelReader reader = ModelReader.expand(policyIncludingBenefits(true), benefitsResolver());
        assertTrue(reader.paths().contains("/Policy/Benefits/Coverage"), "the mounted field survives");
        assertTrue(reader.includedRulesNeedingRelativization().isEmpty(),
                "excludeRules drops the mounted rule, so nothing needs relativization");
        assertFalse(reader.toJson().contains("CoveragePositive"), "the excluded rule is gone from the expanded form");
    }

    // ── failure modes ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void failsFastWhenAnIncludedModelCannotBeResolved() {
        assertThrows(UnresolvedReferenceException.class,
                () -> ModelReader.expand(Fixtures.json("models/storefront.dm.json"), ref -> null));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────────────

    private static String fieldTypeName(ModelReader reader, String path) {
        return reader.element(path).path("Field").path("fieldType").path("type").asText();
    }

    /** Serve the committed catalog.dm.json for the "catalog" include reference. */
    private static ModelResolver catalogResolver() {
        String catalog = Fixtures.json("models/catalog.dm.json");
        return ref -> "catalog".equals(ref) ? catalog : null;
    }

    /** An included insurance-benefits model with one field and one rule (referencing the field by short name). */
    private static String benefitsModel() {
        return """
            { "header": { "id": "benefits", "modelType": "document", "modelVersion": "28.4.0",
                "locales": [{"code":"en_US"}], "modelReferences": [] },
              "content": { "modelInfo": {"name":"benefits"}, "modelRoot": { "rootGroups": [
                { "type":"Group", "id":"/Benefits", "name":"Benefits", "Group": { "repeatability":1, "elements": [
                  { "type":"Field", "id":"/Benefits/Coverage", "name":"Coverage",
                    "Field": { "fieldType": { "type":"NumberType", "NumberType": { "maxFractionalDigits":2 } } } },
                  { "type":"Rule", "id":"/Benefits/CoveragePositive", "name":"CoveragePositive",
                    "Rule": { "errorEntityRelPath":"../Coverage", "errorCode":"COV_POS",
                      "errorCondition":"[Coverage] < 0", "severity":"ERROR", "errorMessage":[] } }
                ] } } ] } } }
            """;
    }

    private static ModelResolver benefitsResolver() {
        String benefits = benefitsModel();
        return ref -> "benefits".equals(ref) ? benefits : null;
    }

    /** A base policy model mounting the benefits include at /Policy/Benefits, optionally excluding its rules. */
    private static String policyIncludingBenefits(boolean excludeRules) {
        String exclude = excludeRules ? ", \"excludeRules\": true" : "";
        return """
            { "header": { "id": "policy", "modelType": "document", "modelVersion": "28.4.0",
                "locales": [{"code":"en_US"}],
                "modelReferences": [{"alias":"benefits","modelType":"document","purpose":"include","reference":"benefits"}] },
              "content": { "modelInfo": {"name":"policy"}, "modelRoot": { "rootGroups": [
                { "type":"Group", "id":"/Policy", "name":"Policy", "Group": { "repeatability":1, "elements": [
                  { "type":"Field", "id":"/Policy/PolicyNo", "name":"PolicyNo",
                    "Field": { "fieldType": { "type":"StringType" } } },
                  { "type":"Group", "id":"/Policy/Benefits", "name":"Benefits",
                    "Group": { "repeatability":1, "modelAlias":"benefits"%EXCLUDE% } }
                ] } } ] } } }
            """.replace("%EXCLUDE%", exclude);
    }
}
