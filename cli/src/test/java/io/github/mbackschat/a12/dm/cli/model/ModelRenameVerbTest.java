package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * {@code model rename --to <newId>} (G27) — change a model's header id. A single-model refactor with a
 * cross-model safety gate: with a workspace (-w) it REFUSES when a sibling includes/imports the old id (the
 * cross-model cascade is out of scope — CLI-SPEC §6), and refuses a new id that collides with a sibling.
 */
class ModelRenameVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A standalone catalog model. */
    private static String catalogJson() {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", c -> c.field("Sku", FieldKind.STRING))
                .build().toJson();
    }

    /** A storefront that includes (mounts) `catalog`. */
    private static String storefrontJson() {
        return DocumentModelDraft.named("storefront").locale("en_US")
                .include("catalog", "catalog")
                .rootGroup("Storefront", s -> s
                        .field("StoreName", FieldKind.STRING)
                        .mount("Items", "catalog"))
                .build().toJson();
    }

    @Test
    void renamesAStandaloneModelId(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("catalog.dm.json");
        Files.writeString(model, catalogJson());
        var res = CliTestSupport.run("-m", model.toString(), "model", "rename", "--to", "inventory");
        assertEquals(0, res.code(), res::err);
        JsonNode env = M.readTree(res.out());
        assertEquals("applied", env.path("outcome").asText());
        assertEquals("inventory", env.path("changed").path("id").asText());
        assertEquals("inventory", M.readTree(Files.readString(model)).path("header").path("id").asText(),
                "the header id is rewritten on disk");
    }

    @Test
    void refusesWhenAWorkspaceSiblingIncludesTheModel(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog.dm.json");
        Files.writeString(catalog, catalogJson());
        Files.writeString(dir.resolve("storefront.dm.json"), storefrontJson());
        String before = Files.readString(catalog);

        var res = CliTestSupport.run("-m", catalog.toString(), "-w", dir.toString(),
                "model", "rename", "--to", "inventory");
        assertEquals(2, res.code());
        JsonNode env = M.readTree(res.out());
        assertEquals("refused", env.path("outcome").asText());
        assertEquals("RK_REFERENCED", env.path("diagnostics").path(0).path("code").asText());
        assertTrue(env.path("data").path("referencedBy").path("models").toString().contains("storefront"),
                () -> "names the including sibling: " + res.out());
        assertEquals(before, Files.readString(catalog), "a refused rename writes nothing");
    }

    @Test
    void refusesWhenTheNewIdCollidesWithASibling(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog.dm.json");
        Files.writeString(catalog, catalogJson());
        Files.writeString(dir.resolve("storefront.dm.json"), storefrontJson());

        var res = CliTestSupport.run("-m", catalog.toString(), "-w", dir.toString(),
                "model", "rename", "--to", "storefront");
        assertEquals(2, res.code());
        assertEquals("RK_NAME_EXISTS", M.readTree(res.out()).path("diagnostics").path(0).path("code").asText());
    }

    @Test
    void rejectsRenamingToTheSameId(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("catalog.dm.json");
        Files.writeString(model, catalogJson());
        assertNotEquals(0, CliTestSupport.run("-m", model.toString(), "model", "rename", "--to", "catalog").code(),
                "renaming to the same id is a no-op error");
    }
}
