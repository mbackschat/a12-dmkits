package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The demonstration gate (CLI-CONFORMANCE-SPEC, the matrix "demonstrated" column) — every worked example in
 * {@code verb-examples.json} (the one the {@code manifest} surfaces to a cold agent) is <b>executed through
 * the tool</b> against a real fixture and must produce its documented outcome. A drifted example — a renamed
 * flag, a now-invalid path, a verb whose behavior changed — fails here, so the "in action" demonstration an
 * agent reads cannot rot into a lie. Every example is <b>read-only</b> by construction (a read, or a mutation
 * under {@code --dry-run}), so it never touches the committed fixtures.
 */
class VerbDemonstrationTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void everyVerbExampleRunsAndProducesItsDocumentedOutcome(@TempDir Path dir) throws Exception {
        JsonNode corpus = corpus();
        int run = 0;
        for (Iterator<String> it = corpus.fieldNames(); it.hasNext();) {
            String verb = it.next();
            if (verb.startsWith("_")) {
                continue; // metadata key (e.g. "_about")
            }
            JsonNode ex = corpus.get(verb);
            List<String> command = materialize(verb, ex, dir);
            CliTestSupport.Result res = CliTestSupport.run(command.toArray(new String[0]));
            String expect = ex.path("expect").asText();
            assertOutcome(verb, expect, res, command);
            run++;
        }
        int ran = run;
        assertTrue(ran >= 40, () -> "the demonstration corpus must exercise the surface; ran=" + ran);
    }

    /** Write the example's model (+ siblings, + inline doc) to {@code dir} and resolve the {@code <...>} placeholders. */
    private static List<String> materialize(String verb, JsonNode ex, Path dir) throws Exception {
        // model-originating verbs (e.g. `model new`) declare no `model` — they make one
        Path model = ex.hasNonNull("model") ? writeResource("models/" + ex.get("model").asText(), dir) : null;
        for (JsonNode sib : ex.path("siblings")) {
            writeResource("models/" + sib.asText(), dir); // co-located so an include resolves the sibling by id
        }
        Path doc = null;
        if (ex.has("doc")) {
            doc = dir.resolve("doc.json");
            Files.writeString(doc, ex.get("doc").toString());
        }
        Path schemaFile = null;   // an external JSON Schema input (model import-jsonschema)
        if (ex.has("schema")) {
            schemaFile = dir.resolve("schema.json");
            Files.writeString(schemaFile, ex.get("schema").toString());
        }
        // a verb that needs an ISOLATED workspace with its own roles.yaml (workspace roles): the shared `dir`
        // accumulates every example's models, so we stage this one's models + roles file in a private subtree.
        Path rolesWorkspace = ex.has("rolesWorkspace") ? materializeRolesWorkspace(ex.get("rolesWorkspace"), dir) : null;
        List<String> out = new ArrayList<>();
        for (JsonNode tok : ex.path("command")) {
            String t = tok.asText();
            out.add(switch (t) {
                case "<model.json>" -> {
                    assertTrue(model != null, () -> verb + " uses <model.json> but declares no `model`");
                    yield model.toString();
                }
                case "<doc.json>" -> {
                    assertTrue(doc != null, () -> verb + " uses <doc.json> but declares no `doc`");
                    yield doc.toString();
                }
                case "<schema.json>" -> {
                    assertTrue(schemaFile != null, () -> verb + " uses <schema.json> but declares no `schema`");
                    yield schemaFile.toString();
                }
                // a directory verb (workspace list): the temp dir, already populated with the example's siblings
                case "<workspace-dir>" -> dir.toString();
                case "<roles-workspace>" -> {
                    assertTrue(rolesWorkspace != null, () -> verb + " uses <roles-workspace> but declares no `rolesWorkspace`");
                    yield rolesWorkspace.toString();
                }
                default -> t;
            });
        }
        return out;
    }

    private static void assertOutcome(String verb, String expect, CliTestSupport.Result res, List<String> command) throws Exception {
        switch (expect) {
            case "read", "preview" -> {
                assertEquals(0, res.code(), () -> verb + " demo failed (" + command + "): " + res.err() + res.out());
                assertEquals(expect, M.readTree(res.out()).path("outcome").asText(),
                        () -> verb + " demo expected outcome=" + expect + ": " + res.out());
            }
            case "raw" -> {
                // a non-envelope verb (export renders an artifact; model new emits a brand-new model document)
                assertEquals(0, res.code(), () -> verb + " demo failed (" + command + "): " + res.err());
                assertFalse(res.out().isBlank(), () -> verb + " demo produced no output: " + command);
            }
            default -> throw new AssertionError(verb + " has an unknown expect '" + expect + "'");
        }
    }

    private static Path writeResource(String classpath, Path dir) throws Exception {
        return writeResourceTo(classpath, dir.resolve(classpath.substring(classpath.lastIndexOf('/') + 1)));
    }

    private static Path writeResourceTo(String classpath, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (InputStream in = VerbDemonstrationTest.class.getClassLoader().getResourceAsStream(classpath)) {
            assertTrue(in != null, () -> "fixture resource present: " + classpath);
            Files.writeString(target, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return target;
    }

    /** Stage an isolated workspace: the spec's models under {@code models/}, its roles file at {@code auth/roles.yaml}. */
    private static Path materializeRolesWorkspace(JsonNode spec, Path dir) throws Exception {
        Path ws = dir.resolve("roles-workspace");
        for (JsonNode m : spec.path("models")) {
            writeResourceTo("models/" + m.asText(), ws.resolve("models").resolve(basename(m.asText())));
        }
        writeResourceTo("models/" + spec.get("roles").asText(), ws.resolve("auth").resolve("roles.yaml"));
        return ws;
    }

    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static JsonNode corpus() throws Exception {
        try (InputStream in = VerbDemonstrationTest.class.getResourceAsStream("/verb-examples.json")) {
            assertTrue(in != null, "verb-examples.json present on the classpath");
            return M.readTree(in);
        }
    }
}
