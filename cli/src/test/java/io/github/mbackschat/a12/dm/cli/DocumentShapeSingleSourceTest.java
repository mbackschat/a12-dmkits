package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code --instance} document shape is described in <b>exactly one</b> schema
 * ({@code document-instance.schema.json}), and every other schema points at it rather than restating it.
 *
 * <p>This guard exists because the restatement had already gone wrong, in the way a copy always does — it kept a
 * fact the original had corrected. {@code eval-doc.schema.json} told an agent the instance is "a nested tree under
 * {@code \"fields\"}" long after the wrapper was removed, while {@code document-instance.schema.json}, served by the
 * <em>same</em> {@code schema model eval} call, said "there is NO wrapper key". The tool itself refuses the wrapper
 * — and its refusal diagnostic points the agent at {@code dmtool schema model eval}, i.e. straight back at the
 * wrong instruction. Three surfaces, two of them right, and nothing compared them.
 *
 * <p>So the assertions are: the wrapper claim appears nowhere, no output schema re-describes the tree, and the
 * behaviour the single source states is the behaviour the tool has. The last one is what keeps the first two from
 * being a spell-check — a description can be internally consistent and still wrong about the program.
 */
class DocumentShapeSingleSourceTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Every bundled schema, so a NEW schema that restates the shape is in scope without being registered here. */
    private static final Path SCHEMA_DIR = Path.of("src/main/resources/schema");

    /** Phrases that only make sense inside a description OF the instance tree — i.e. a restatement. */
    private static final List<String> SHAPE_VOCABULARY =
            List.of("repeatable group = array", "field = its text value", "present-but-unfilled");

    @Test
    void onlyTheDocumentInstanceSchemaDescribesTheInstanceTree() throws Exception {
        List<Path> schemas;
        try (var files = Files.list(SCHEMA_DIR)) {
            schemas = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(schemas.size() > 20, () -> "the schema dir must be readable from the test's CWD: " + SCHEMA_DIR);

        for (Path schema : schemas) {
            if (schema.getFileName().toString().equals("document-instance.schema.json")) {
                continue;   // the single source — this is the one place the shape IS described
            }
            String text = Files.readString(schema);
            for (String phrase : SHAPE_VOCABULARY) {
                assertFalse(text.contains(phrase),
                        () -> schema.getFileName() + " restates the --instance tree shape (\"" + phrase + "\"). "
                                + "Point at document-instance.schema.json instead: a copy keeps whatever the "
                                + "original has since corrected, which is exactly how the removed `fields` wrapper "
                                + "survived in eval-doc long after the tool started refusing it");
            }
        }
    }

    /** The wrapper is gone from the tool; no schema may still be teaching it. */
    @Test
    void noSchemaStillTeachesTheRemovedFieldsWrapper() throws Exception {
        try (var files = Files.list(SCHEMA_DIR)) {
            for (Path schema : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String text = Files.readString(schema);
                assertFalse(text.contains("tree under \\\"fields\\\"") || text.contains("under \\\"fields\\\""),
                        () -> schema.getFileName() + " still documents the removed {\"fields\": …} wrapper");
            }
        }
    }

    /**
     * The behaviour half: what the single source says is what the tool does. Without this the two checks above
     * only prove the descriptions agree with each other.
     */
    @Test
    void theToolAcceptsTheShapeTheSingleSourceDescribesAndRefusesTheWrapper(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("order-ruled.dm.json");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("models/order-ruled.dm.json")) {
            assertTrue(in != null, "fixture present");
            Files.writeString(model, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }

        Path unwrapped = dir.resolve("unwrapped.json");
        Files.writeString(unwrapped, "{\"Order\": {\"CustomerName\": \"Acme\"}}");
        CliTestSupport.Result ok = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", unwrapped.toString());
        assertEquals(0, ok.code(),
                () -> "a root group name as a top-level key is what document-instance.schema describes: " + ok.err());
        assertEquals("read", M.readTree(ok.out()).path("outcome").asText(), ok::out);

        Path wrapped = dir.resolve("wrapped.json");
        Files.writeString(wrapped, "{\"fields\": {\"Order\": {\"CustomerName\": \"Acme\"}}}");
        CliTestSupport.Result refused = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", wrapped.toString());
        assertFalse(refused.code() == 0,
                () -> "the wrapper must stay refused — a schema that documents it would then be teaching a form "
                        + "the tool accepts, which is a worse bug than the one this guard was written for: "
                        + refused.out());
    }
}
