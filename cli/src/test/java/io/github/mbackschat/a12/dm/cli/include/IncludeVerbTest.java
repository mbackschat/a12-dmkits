package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

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
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

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
                "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog",
                "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("catalog", j.path("changed").path("added").asText());
        assertEquals("/Subscription/Catalog", j.path("changed").path("mountedAt").asText());
        assertEquals(java.util.List.of("/Subscription/Catalog"),
                ModelDocument.read(Files.readString(model)).mountsOfInclude("catalog"),
                "the mount names the included model in its includeConfig");
    }

    @Test
    void addMountsARepeatableInclude(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        var res = CliTestSupport.run("-m", model.toString(), "include", "add",
                "--reference", "catalog",
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
                "--reference", "catalog",
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
                "--reference", "catalog",
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
            if ("catalog".equals(inc.path("reference").asText())) {
                hasCatalog = true;
            }
        }
        assertTrue(hasCatalog, "expected the included model 'catalog' in data.includes");
    }

    @Test
    void readWithAReferenceReadsThatOneInclude(@TempDir Path dir) throws Exception {
        // unified read: a specifier reads one (here the included model's id), no arg lists — symmetric with the family.
        Path model = subscriptionWithCatalog(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "include", "add",
                "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog",
                "-w", dir.toString()).code());
        var res = CliTestSupport.run("-m", model.toString(), "include", "read", "catalog");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        assertEquals("catalog", j.path("data").path("reference").asText());
    }

    @Test
    void readWithAnUnknownReferenceFails(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        var res = CliTestSupport.run("-m", model.toString(), "include", "read", "nope");
        assertFalse(res.code() == 0, "an unknown reference is a clean failure");
    }

    @Test
    void setReferenceRepointsAnIncludeInPlace(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        Files.writeString(dir.resolve("catalog2.dm.json"),
                DocumentModelDraft.named("catalog2").locale("en_US").locale("de_DE")
                        .rootGroup("Catalog2", g -> g.field("Code", FieldKind.STRING))
                        .build().toJson());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "include", "add",
                "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog",
                "-w", dir.toString()).code());
        var res = CliTestSupport.run("-m", model.toString(), "include", "set-reference", "catalog",
                "--to", "catalog2", "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("catalog", j.path("changed").path("reference").asText());
        assertEquals("catalog2", j.path("changed").path("to").asText());
        ModelDocument written = ModelDocument.read(Files.readString(model));
        assertEquals(java.util.List.of("/Subscription/Catalog"), written.mountsOfInclude("catalog2"),
                "the mount keeps its place and now names the new model");
        assertEquals(java.util.List.of(), written.mountsOfInclude("catalog"), "and nothing names the old one");
    }

    @Test
    void setReferenceOnAnUnknownReferenceRefuses(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        var res = CliTestSupport.run("-m", model.toString(), "include", "set-reference", "nope",
                "--to", "catalog", "-w", dir.toString());
        assertEquals(2, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertEquals("RK_NO_SUCH_INCLUDE", j.path("diagnostics").get(0).path("code").asText());
    }

    @Test
    void removesAnInclude(@TempDir Path dir) throws Exception {
        Path model = subscriptionWithCatalog(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "include", "add",
                "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog",
                "-w", dir.toString()).code());
        var res = CliTestSupport.run("-m", model.toString(), "include", "remove", "catalog",
                "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertFalse(Files.readString(model).contains("\"includeConfig\""));
    }
}
