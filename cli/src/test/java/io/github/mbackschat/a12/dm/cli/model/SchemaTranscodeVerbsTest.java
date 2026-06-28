package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code model import-jsonschema} / {@code model export-jsonschema} — the JSON-Schema transcode verbs. Import
 * turns a JSON Schema into a kernel-valid model (artifact to stdout, omissions reported on stderr, tunable via
 * the MappingProfile flags); export renders the {@code -m} model back as a JSON Schema. Both keep artifact
 * mode (raw to stdout, or {@code -o} + a write envelope).
 */
class SchemaTranscodeVerbsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String ORDER_SCHEMA = """
            { "title": "Order", "type": "object", "required": ["id"],
              "properties": { "id": { "type": "string" }, "quantity": { "type": "integer" } } }
            """;

    @Test
    void importProducesAKernelValidModelToStdout(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("order.schema.json");
        Files.writeString(schema, ORDER_SCHEMA);

        CliTestSupport.Result res = CliTestSupport.run("model", "import-jsonschema", "--schema", schema.toString());
        assertEquals(0, res.code(), res::err);

        // stdout IS the DM-JSON artifact — round-trip it through `model check`
        Path model = dir.resolve("Order.dm.json");
        Files.writeString(model, res.out());
        CliTestSupport.Result checked = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, checked.code(), checked::err);
        assertTrue(M.readTree(checked.out()).path("valid").asBoolean(), () -> "imported model is kernel-valid: " + checked.out());
    }

    @Test
    void importWritesToFileWithDashOAndConfirms(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("order.schema.json");
        Files.writeString(schema, ORDER_SCHEMA);
        Path model = dir.resolve("Order.dm.json");

        CliTestSupport.Result res = CliTestSupport.run(
                "model", "import-jsonschema", "--schema", schema.toString(), "-o", model.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(Files.exists(model), "the model file is written");
        JsonNode env = M.readTree(res.out());
        assertEquals("applied", env.path("outcome").asText(), () -> "with -o stdout confirms with an applied envelope: " + res.out());
        assertTrue(env.path("written").asBoolean(), () -> "written:true: " + res.out());
    }

    @Test
    void importReportsAnOmissionOnStderr(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("price.schema.json");
        Files.writeString(schema, """
                { "type": "object", "properties": { "price": { "type": "number" } } }
                """);   // a bare `number` is omitted by default (no a12 scale) — must be reported, not silent

        CliTestSupport.Result res = CliTestSupport.run("model", "import-jsonschema", "--schema", schema.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(res.err().contains("number"), () -> "the omission rides the stderr report: " + res.err());
    }

    @Test
    void aProfileFlagChangesTheImportOutcome(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("price.schema.json");
        Files.writeString(schema, """
                { "title": "Pricing", "type": "object", "properties": { "price": { "type": "number" } } }
                """);

        CliTestSupport.Result res = CliTestSupport.run("model", "import-jsonschema", "--schema", schema.toString(),
                "--number-scale", "DEFAULT_SCALE", "--default-scale", "2");
        assertEquals(0, res.code(), res::err);
        // now the bare number IS imported (default-scale 2) — the field exists, and the model is kernel-valid
        Path model = dir.resolve("Pricing.dm.json");
        Files.writeString(model, res.out());
        CliTestSupport.Result checked = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, checked.code(), checked::err);
        assertTrue(res.out().contains("price"), () -> "DEFAULT_SCALE imports the number field: " + res.out());
    }

    @Test
    void exportRendersTheModelAsJsonSchema(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("m.dm.json");
        assertEquals(0, CliTestSupport.run(
                "model", "new", "--id", "Order", "--locale", "en_US", "--root", "Order", "-o", model.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Order", "--name", "Code", "--kind", "STRING").code());

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "export-jsonschema");
        assertEquals(0, res.code(), res::err);
        JsonNode schema = M.readTree(res.out());
        assertEquals("object", schema.path("type").asText(), () -> "export emits a JSON Schema object: " + res.out());
        assertTrue(schema.path("properties").has("Code"), () -> "the field exports as a property: " + res.out());
    }

    @Test
    void importThenExportRoundTripsTheStructure(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("order.schema.json");
        Files.writeString(schema, ORDER_SCHEMA);
        Path model = dir.resolve("Order.dm.json");
        assertEquals(0, CliTestSupport.run(
                "model", "import-jsonschema", "--schema", schema.toString(), "-o", model.toString()).code());

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "export-jsonschema");
        assertEquals(0, res.code(), res::err);
        JsonNode out = M.readTree(res.out());
        assertEquals("object", out.path("type").asText());
        assertTrue(out.path("properties").has("id") && out.path("properties").has("quantity"),
                () -> "import→export preserves the field structure: " + res.out());
    }
}
