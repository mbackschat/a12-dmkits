package io.github.mbackschat.a12.dm.cli;

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

/**
 * {@code dmtool doc info} — the single-model header dashboard: identity + the super/subtype graph + every
 * outbound model reference (include / type-def import) <b>resolved to its file</b> via {@code --include-dir}
 * (default the model's folder) + compact counts. The non-redundancy contract is the point — it owns the
 * facet no other read verb does (identity, type-def imports, super/subtypes), emitting only counts (not the
 * field/rule/config <em>contents</em> that `doc describe`/`doc read`/`config read` own).
 */
class DocInfoCommandTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A product-catalog workspace flat in {@code dir}; returns the path of Product_Movie (the subtype). */
    private static Path catalog(Path dir) throws Exception {
        write(dir, "CatalogTypes.dm.json",
                DocumentModelDraft.typeDefModel("CatalogTypes").locale("en_US")
                        .typeDef("Sku", t -> t.string(s -> { })).build().toJson());
        write(dir, "Product_Base.dm.json",
                DocumentModelDraft.named("Product_Base").locale("en_US")
                        .label("en_US", "Product")
                        .annotation("abstract", "true")
                        .annotation("subTypes", "Product_Movie")
                        .annotation("owner", "catalog-team")
                        .importTypeDefs("CatalogTypes")
                        .rootGroup("Product", g -> g.field("Title", FieldKind.STRING)).build().toJson());
        Path movie = dir.resolve("Product_Movie.dm.json");
        Files.writeString(movie,
                DocumentModelDraft.named("Product_Movie").locale("en_US")
                        .annotation("superTypes", "Product_Base")
                        .include("base", "Product_Base")
                        .rootGroup("Movie", g -> g.field("RuntimeMinutes", FieldKind.NUMBER)).build().toJson());
        return movie;
    }

    @Test
    void readsIdentitySuperSubtypeAndResolvedReferences(@TempDir Path dir) throws Exception {
        Path movie = catalog(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", movie.toString(), "doc", "info");
        assertEquals(0, res.code(), res::err);

        JsonNode env = M.readTree(res.out());
        assertEquals("doc", env.path("target").asText());
        assertEquals("info", env.path("op").asText());
        assertEquals("read", env.path("outcome").asText());

        JsonNode data = env.path("data");
        assertEquals("Product_Movie", data.path("id").asText());
        assertEquals("document", data.path("modelType").asText());
        assertFalse(data.path("modelVersion").asText().isBlank(), "identity carries the model version");
        assertEquals("Product_Base", data.path("superTypes").path(0).asText());
        // the include cross-resolves to the sibling file (default --include-dir = the model's folder)
        assertEquals("base", data.path("includes").path(0).path("alias").asText());
        assertEquals("Product_Base.dm.json", data.path("includes").path(0).path("resolvedPath").asText());
    }

    @Test
    void abstractBaseSurfacesSubtypesImportsAndNonConventionAnnotations(@TempDir Path dir) throws Exception {
        catalog(dir);
        JsonNode data = M.readTree(
                CliTestSupport.run("-m", dir.resolve("Product_Base.dm.json").toString(), "doc", "info").out()).path("data");

        assertTrue(data.path("abstract").asBoolean(), "the abstract base is flagged");
        assertEquals("Product_Movie", data.path("subTypes").path(0).asText());
        assertEquals("CatalogTypes", data.path("typedefImports").path(0).path("ref").asText());
        assertEquals("CatalogTypes.dm.json", data.path("typedefImports").path(0).path("resolvedPath").asText());
        // the convention keys are parsed into their own fields; `annotations` carries only the rest
        assertEquals("catalog-team", data.path("annotations").path("owner").asText());
        assertTrue(data.path("annotations").path("abstract").isMissingNode(),
                "the convention keys are not duplicated inside `annotations`");
    }

    @Test
    void countsAreCompactAndIncludeRulesAndComputations(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir); // 1 rule + 1 computation
        JsonNode counts = M.readTree(
                CliTestSupport.run("-m", model.toString(), "doc", "info").out()).path("data").path("counts");
        assertEquals(1, counts.path("rules").asInt());
        assertEquals(1, counts.path("computations").asInt());
        assertTrue(counts.path("groups").asInt() >= 1 && counts.path("fields").asInt() >= 1, "structural counts present");
    }

    private static void write(Path dir, String name, String json) throws Exception {
        Files.writeString(dir.resolve(name), json);
    }
}
