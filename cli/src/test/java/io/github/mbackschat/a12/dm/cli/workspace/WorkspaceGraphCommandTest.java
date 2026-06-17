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

/**
 * {@code dmtool workspace graph} — the model-inheritance graph view. Asserts the read-envelope nodes/edges
 * shape (with the resolved flag on an in-scope vs out-of-scope edge), and the {@code tree}/{@code dot}
 * renderings. The conformance harness covers the self-describing surface.
 */
class WorkspaceGraphCommandTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** An abstract base naming two subtypes, but only one (Movie) present — so Book is an unresolved edge. */
    private static Path hierarchy(Path dir) throws Exception {
        Files.writeString(dir.resolve("Product_Base.dm.json"),
                DocumentModelDraft.named("Product_Base").locale("en_US")
                        .annotation("abstract", "true")
                        .annotation("subTypes", "Product_Movie,Product_Book")
                        .rootGroup("Product", g -> g.field("Title", FieldKind.STRING)).build().toJson());
        Files.writeString(dir.resolve("Product_Movie.dm.json"),
                DocumentModelDraft.named("Product_Movie").locale("en_US")
                        .annotation("superTypes", "Product_Base")
                        .rootGroup("Movie", g -> g.field("RuntimeMinutes", FieldKind.NUMBER)).build().toJson());
        return dir;
    }

    @Test
    void jsonCarriesNodesAndResolvedEdges(@TempDir Path dir) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("workspace", "graph", hierarchy(dir).toString());
        assertEquals(0, res.code(), res::err);
        JsonNode env = M.readTree(res.out());
        assertEquals("workspace", env.path("target").asText());
        assertEquals("graph", env.path("op").asText());
        assertEquals("read", env.path("outcome").asText());

        JsonNode data = env.path("data");
        assertTrue(node(data, "Product_Base").path("abstract").asBoolean(), "the base node is flagged abstract");
        assertTrue(edgeResolved(data, "Product_Movie", "Product_Base"), "the in-scope subtype edge resolves");
        assertFalse(edgeResolved(data, "Product_Book", "Product_Base"), "the absent subtype is an unresolved edge");
    }

    @Test
    void treeRendersTheHierarchyWithMarkers(@TempDir Path dir) throws Exception {
        String out = CliTestSupport.run("workspace", "graph", hierarchy(dir).toString(), "--format", "tree").out();
        assertTrue(out.contains("Product_Base (abstract)"), () -> "root marked abstract: " + out);
        assertTrue(out.contains("- Product_Movie"), () -> "subtype nested under the base: " + out);
        assertTrue(out.contains("Product_Book (unresolved)"), () -> "the out-of-scope subtype is flagged: " + out);
    }

    @Test
    void dotEmitsAGraphvizDigraph(@TempDir Path dir) throws Exception {
        String out = CliTestSupport.run("workspace", "graph", hierarchy(dir).toString(), "--format", "dot").out();
        assertTrue(out.contains("digraph workspace {"), () -> "a dot digraph: " + out);
        assertTrue(out.contains("\"Product_Movie\" -> \"Product_Base\""), () -> "the is-a edge: " + out);
    }

    private static JsonNode node(JsonNode data, String id) {
        for (JsonNode n : data.path("nodes")) {
            if (id.equals(n.path("id").asText())) {
                return n;
            }
        }
        throw new AssertionError("no node '" + id + "' in " + data);
    }

    private static boolean edgeResolved(JsonNode data, String subtype, String supertype) {
        for (JsonNode e : data.path("edges")) {
            if (subtype.equals(e.path("subtype").asText()) && supertype.equals(e.path("supertype").asText())) {
                return e.path("resolved").asBoolean();
            }
        }
        throw new AssertionError("no edge " + subtype + "→" + supertype + " in " + data);
    }
}
