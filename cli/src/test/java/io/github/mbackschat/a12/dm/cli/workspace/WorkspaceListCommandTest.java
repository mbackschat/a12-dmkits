package io.github.mbackschat.a12.dm.cli.workspace;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * {@code dmtool workspace list} — the directory "ls" verb. Asserts the read-envelope shape, the
 * cross-resolution of includes/type-def imports to their files within the scan, the super/subtype facts,
 * {@code --recursive} widening the scope, the {@code --validate} validity flag, and the {@code --format table}
 * human view. The conformance harness (manifest/schema/demonstration/matrix) covers the self-describing surface.
 */
class WorkspaceListCommandTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A small product-catalog workspace written flat into {@code dir}; returns it. */
    private static Path catalog(Path dir) throws Exception {
        write(dir, "CatalogTypes.dm.json",
                DocumentModelDraft.typeDefModel("CatalogTypes").locale("en_US")
                        .typeDef("Sku", t -> t.string(s -> { }))
                        .build().toJson());
        write(dir, "Product_Base.dm.json",
                DocumentModelDraft.named("Product_Base").locale("en_US")
                        .annotation("abstract", "true")
                        .annotation("subTypes", "Product_Movie")
                        .importTypeDefs("CatalogTypes")
                        .rootGroup("Product", g -> g.field("Title", FieldKind.STRING))
                        .build().toJson());
        write(dir, "Product_Movie.dm.json",
                DocumentModelDraft.named("Product_Movie").locale("en_US")
                        .annotation("superTypes", "Product_Base")
                        .rootGroup("Movie", g -> g.field("RuntimeMinutes", FieldKind.NUMBER))
                        .build().toJson());
        return dir;
    }

    @Test
    void listsTheModelsAsAReadEnvelopeWithResolvedReferences(@TempDir Path dir) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("workspace", "list", catalog(dir).toString());
        assertEquals(0, res.code(), res::err);

        JsonNode env = M.readTree(res.out());
        assertEquals("workspace", env.path("target").asText());
        assertEquals("list", env.path("op").asText());
        assertEquals("read", env.path("outcome").asText());
        JsonNode data = env.path("data");
        assertEquals(3, data.path("count").asInt());

        JsonNode base = model(data, "Product_Base");
        assertTrue(base.path("abstract").asBoolean(), "the abstract base is flagged");
        assertEquals("Product_Movie", base.path("subTypes").path(0).asText());
        assertEquals("CatalogTypes", base.path("typedefImports").path(0).path("ref").asText());
        assertEquals("CatalogTypes.dm.json", base.path("typedefImports").path(0).path("resolvedPath").asText(),
                "the type-def import cross-resolves to its provider file");

        assertEquals("Product_Base", model(data, "Product_Movie").path("superTypes").path(0).asText());
        assertEquals("Sku", model(data, "CatalogTypes").path("providesTypedefs").path(0).asText());
    }

    @Test
    void theDirComesFromTheUniversalWorkspaceOptionWhenThePositionalIsOmitted(@TempDir Path dir) throws Exception {
        // probe 01-de Finding 5: workspace verbs ignored the inherited -w an agent had already set and demanded
        // the dir positionally. The dir now falls back to -w/--workspace (the same universal option that
        // resolves a -m model's includes), so `dmtool -w <dir> workspace list` works like the agent expected.
        CliTestSupport.Result res = CliTestSupport.run("-w", catalog(dir).toString(), "workspace", "list");
        assertEquals(0, res.code(), res::err);
        assertEquals(3, M.readTree(res.out()).path("data").path("count").asInt(),
                () -> "the -w root was used as the scan dir: " + res.out());
    }

    @Test
    void noDirAndNoWorkspaceGivesACorrectiveNamingBothForms(@TempDir Path dir) {
        CliTestSupport.Result res = CliTestSupport.run("workspace", "list");
        assertEquals(2, res.code(), () -> "no dir + no -w → clean fail: " + res.out() + res.err());
        assertTrue(res.err().contains("-w") || res.out().contains("-w"),
                () -> "the corrective names the -w/--workspace fallback: " + res.err() + res.out());
    }

    @Test
    void recursiveWidensTheScopeSoASubfolderIncludeResolves(@TempDir Path dir) throws Exception {
        catalog(dir);
        Path lib = Files.createDirectory(dir.resolve("lib"));
        write(lib, "Catalog_Shared.dm.json",
                DocumentModelDraft.named("Catalog_Shared").locale("en_US")
                        .rootGroup("Shared", g -> g.field("Note", FieldKind.STRING))
                        .build().toJson());
        // re-point Product_Movie at an include that lives under lib/
        write(dir, "Product_Movie.dm.json",
                ModelDocument.read(Files.readString(dir.resolve("Product_Movie.dm.json")))
                        .addInclude("shared", "Catalog_Shared").toJson());

        JsonNode flat = M.readTree(CliTestSupport.run("workspace", "list", dir.toString()).out()).path("data");
        assertEquals(3, flat.path("count").asInt(), "a flat scan does not descend into lib/");
        assertTrue(model(flat, "Product_Movie").path("includes").path(0).path("resolvedPath").isNull(),
                "the include is out of scope for a flat scan");

        JsonNode deep = M.readTree(CliTestSupport.run("workspace", "list", dir.toString(), "--recursive").out()).path("data");
        assertEquals(4, deep.path("count").asInt());
        assertEquals("lib/Catalog_Shared.dm.json",
                model(deep, "Product_Movie").path("includes").path(0).path("resolvedPath").asText());
    }

    @Test
    void validateFlagsEachModelKernelValidOrNot(@TempDir Path dir) throws Exception {
        catalog(dir);
        // a model that READS as DM-JSON but mounts a dangling include — kernel-invalid (the reference can't resolve)
        write(dir, "Broken.dm.json",
                ModelDocument.read(DocumentModelDraft.named("Broken").locale("en_US")
                        .rootGroup("Root", g -> g.field("X", FieldKind.STRING)).build().toJson())
                        .addInclude("missing", "NoSuchModel").mount("", "MissingMount", "missing").toJson());

        JsonNode data = M.readTree(
                CliTestSupport.run("workspace", "list", dir.toString(), "--validate").out()).path("data");
        assertTrue(model(data, "Product_Base").path("valid").asBoolean(), "a self-contained model is valid");
        assertFalse(model(data, "Broken").path("valid").asBoolean(), "a dangling mounted include makes it invalid");
    }

    @Test
    void formatTableRendersAnAlignedHumanListing(@TempDir Path dir) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("workspace", "list", catalog(dir).toString(), "--format", "table");
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().contains("ID") && res.out().contains("SUPERTYPES") && res.out().contains("PATH"),
                () -> "the table has a header row: " + res.out());
        assertTrue(res.out().contains("Product_Movie"), () -> "the table lists each model: " + res.out());
    }

    @Test
    void aMissingDirectoryFailsCleanly(@TempDir Path dir) {
        CliTestSupport.Result res = CliTestSupport.run("workspace", "list", dir.resolve("nope").toString());
        assertEquals(2, res.code());
        assertTrue(res.err().contains("not a directory"), res::err);
    }

    private static JsonNode model(JsonNode data, String id) {
        for (JsonNode m : data.path("models")) {
            if (id.equals(m.path("id").asText())) {
                return m;
            }
        }
        throw new AssertionError("no model '" + id + "' in " + data);
    }

    private static void write(Path dir, String name, String json) throws Exception {
        Files.writeString(dir.resolve(name), json);
    }
}
