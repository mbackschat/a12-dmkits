package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The target-first {@code include} group (CLI-SPEC §5) — model includes: {@code add} (declare a header
 * model-reference + mount it as a group; the referenced model is resolved + kernel-validated via
 * {@code -w/--workspace}), {@code read} (lists the declared includes), {@code remove} (kernel-gated; a rule
 * still referencing the mounted content is a {@code rejected} envelope).
 */
class IncludeVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The subscription fixture + a sibling {@code catalog} model in the same dir (resolved by header id). */
    private static Path subscriptionWithCatalog(Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Files.writeString(dir.resolve("catalog.dm.json"),
                DocumentModelDraft.named("catalog").locale("en_US").locale("de_DE")
                        .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING))
                        .build().toJson());
        return model;
    }

    @Test
    void addDeclaresAndMountsAnInclude(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        var res = CliTestSupport.run("-m", model.toString(), "include", "add",
                "--alias", "catalog", "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog",
                "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("catalog", j.path("changed").path("added").asText());
        assertEquals("/Subscription/Catalog", j.path("changed").path("mountedAt").asText());
        String written = Files.readString(model);
        assertTrue(written.contains("\"modelAlias\""));
        assertTrue(written.contains("\"reference\""));
    }

    @Test
    void addMountsARepeatableInclude(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        var res = CliTestSupport.run("-m", model.toString(), "include", "add",
                "--alias", "catalog", "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog", "--repeatability", "3",
                "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals(3, j.path("changed").path("repeatability").asInt(), "the repeatability is reported");
        assertTrue(Files.readString(model).contains("\"repeatability\" : 3"),
                "the mount is emitted as a repeatable group (kernel-validated by the add)");
    }

    @Test
    void addMountsAnIncludeExcludingItsRules(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        var res = CliTestSupport.run("-m", model.toString(), "include", "add",
                "--alias", "catalog", "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog", "--exclude-rules",
                "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.path("changed").path("excludeRules").asBoolean(), "the exclusion is reported in `changed`");
        assertTrue(Files.readString(model).contains("\"excludeRules\" : true"),
                "the mount carries excludeRules (kernel-validated by the add)");
    }

    @Test
    void readListsTheDeclaredIncludes(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "include", "add",
                "--alias", "catalog", "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog",
                "-w", dir.toString()).code());
        var res = CliTestSupport.run("-m", model.toString(), "include", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        JsonNode incs = j.path("data").path("includes");
        assertTrue(incs.isArray() && incs.size() >= 1);
        boolean hasCatalog = false;
        for (JsonNode inc : incs) {
            if ("catalog".equals(inc.path("alias").asText())) {
                hasCatalog = true;
            }
        }
        assertTrue(hasCatalog, "expected an include aliased 'catalog' in data.includes");
    }

    @Test
    void removesAnInclude(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "include", "add",
                "--alias", "catalog", "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog",
                "-w", dir.toString()).code());
        var res = CliTestSupport.run("-m", model.toString(), "include", "remove", "catalog",
                "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertFalse(Files.readString(model).contains("\"modelAlias\""));
    }
}
