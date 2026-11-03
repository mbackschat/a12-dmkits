package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.ModelReader;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;

/**
 * The DM 29.4 include shape the kits write and read (KF651): a mount names its model in {@code includeConfig},
 * the header carries one alias-less entry per distinct mounted model (the A12 Tools and migrator shape), and a
 * DM 28 {@code modelAlias} is refused rather than lost. Own domain: a warehouse mounting a shared address model.
 */
class IncludeConfigShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ADDRESS = DocumentModelDraft.named("address").locale("en_US")
            .rootGroup("Address", address -> address.field("City", FieldKind.STRING))
            .build().toJson();

    private static String warehouse() {
        return DocumentModelDraft.named("warehouse").locale("en_US")
                .importTypeDefs("units")
                .rootGroup("Warehouse", warehouse -> warehouse
                        .field("Code", FieldKind.STRING)
                        .mount("Site", "address")
                        .mount("Billing", "address", MountOptions.single().excludingRules().asModelRoot()))
                .build().toJson();
    }

    private static JsonNode tree(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void aMountWritesItsModelInIncludeConfigLeavingTheDefaultsAbsent() throws Exception {
        JsonNode site = tree(warehouse()).findParents("name").stream()
                .filter(node -> "Site".equals(node.path("name").asText())).findFirst().orElseThrow();
        assertEquals("{\"reference\":\"address\"}", site.path("Group").path("includeConfig").toString());
        assertFalse(site.path("Group").has("modelAlias"));
        JsonNode billing = tree(warehouse()).findParents("name").stream()
                .filter(node -> "Billing".equals(node.path("name").asText())).findFirst().orElseThrow();
        assertEquals("{\"reference\":\"address\",\"includeLevel\":\"MODEL_ROOT\",\"excludeRules\":true}",
                billing.path("Group").path("includeConfig").toString());
    }

    @Test
    void theHeaderCarriesOneAliasLessEntryPerDistinctMountedModel() throws Exception {
        JsonNode references = tree(warehouse()).path("header").path("modelReferences");
        assertEquals("[{\"modelType\":\"document\",\"purpose\":\"include\",\"reference\":\"address\"},"
                        + "{\"modelType\":\"document\",\"purpose\":\"typeDefinitions\",\"reference\":\"units\"}]",
                references.toString());
    }

    @Test
    void rePointingOntoAnAlreadyIncludedModelKeepsOneHeaderEntry() {
        ModelDocument doc = ModelDocument.read(warehouse()).mount("/Warehouse", "Depot", "depot");
        doc.setIncludeReference("depot", "address");
        assertEquals(List.of(new ModelDocument.Include("address")), doc.includes());
        assertEquals(List.of("/Warehouse/Site", "/Warehouse/Billing", "/Warehouse/Depot"),
                doc.mountsOfInclude("address"));
    }

    @Test
    void aMountTheHeaderOmitsIsStillAnInclude() throws Exception {
        ObjectNode headerless = (ObjectNode) tree(warehouse());
        ((ObjectNode) headerless.path("header")).putArray("modelReferences");
        assertEquals(List.of(new ModelDocument.Include("address")),
                ModelDocument.read(headerless.toString()).includes());
    }

    @Test
    void theReaderRefusesAStaleModelAliasThatA12WouldDropSilently() throws Exception {
        String stale = withSite(site -> {
            site.remove("includeConfig");
            site.put("modelAlias", "address");
        });
        var refused = assertThrows(ModelInputException.class, () -> ModelReader.expand(stale, ref -> ADDRESS));
        assertTrue(refused.getMessage().contains("/Warehouse/Site"), refused.getMessage());
        assertTrue(refused.getMessage().contains("includeConfig"), refused.getMessage());
    }

    @Test
    void theReaderRefusesAMountThatNamesNoModel() throws Exception {
        String unnamed = withSite(site -> ((ObjectNode) site.path("includeConfig")).put("reference", " "));
        assertThrows(UnresolvedReferenceException.class, () -> ModelReader.expand(unnamed, ref -> ADDRESS));
    }

    /** The warehouse with its {@code Site} mount's group body edited in place. */
    private static String withSite(Consumer<ObjectNode> edit) throws Exception {
        JsonNode root = tree(warehouse());
        edit.accept((ObjectNode) root.findParents("name").stream()
                .filter(node -> "Site".equals(node.path("name").asText())).findFirst().orElseThrow().path("Group"));
        return root.toString();
    }
}
