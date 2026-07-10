package io.github.mbackschat.a12.dm.cli.model;

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
    void theConversionFlagIsStringFormatNotFormat(@TempDir Path dir) throws Exception {
        // T5: the string-`format` conversion strategy is --string-format, renamed from --format so it is
        // no longer overloaded against the OUTPUT-rendering --format on workspace list/graph. Clean break.
        Path schema = dir.resolve("order.schema.json");
        Files.writeString(schema, ORDER_SCHEMA);
        CliTestSupport.Result ok = CliTestSupport.run(
                "model", "import-jsonschema", "--schema", schema.toString(), "--string-format", "IGNORE");
        assertEquals(0, ok.code(), ok::err);

        CliTestSupport.Result gone = CliTestSupport.run(
                "model", "import-jsonschema", "--schema", schema.toString(), "--format", "IGNORE");
        assertFalse(gone.code() == 0, () -> "the conversion --format was renamed --string-format (clean break, no alias): " + gone.err());
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

    private static final String NAME_AND_PRICE = """
            { "title": "Pricing", "type": "object",
              "properties": { "name": { "type": "string" }, "price": { "type": "number" } } }
            """;

    @Test
    void theWriteEnvelopeCarriesTheStructuredReportForAnAgent(@TempDir Path dir) throws Exception {
        // an agent's natural path is -o (a file it can then -m edit) + reading stdout; the omissions it must
        // act on must be MACHINE-READABLE there, not only prose on stderr (the agent-usage reflection). --strict
        // makes the bare number a genuine omission to carry.
        Path schema = dir.resolve("price.schema.json");
        Files.writeString(schema, NAME_AND_PRICE);
        Path model = dir.resolve("Pricing.dm.json");

        CliTestSupport.Result res = CliTestSupport.run(
                "model", "import-jsonschema", "--schema", schema.toString(), "--strict", "-o", model.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode report = M.readTree(res.out()).path("changed").path("report");
        assertEquals(1, report.path("omitted").asInt(), () -> "the envelope reports the omission count: " + res.out());
        assertTrue(report.path("notes").isArray() && !report.path("notes").isEmpty(),
                () -> "the to-do notes ride the structured envelope, not only stderr: " + res.out());
        assertTrue(report.path("notes").get(0).asText().contains("price"),
                () -> "the to-do names the omitted property: " + res.out());
    }

    @Test
    void bestEffortFlagsTheGuessedScaleOnStderr(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("price.schema.json");
        Files.writeString(schema, NAME_AND_PRICE);   // a bare number imports at a guessed scale by default

        CliTestSupport.Result res = CliTestSupport.run("model", "import-jsonschema", "--schema", schema.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().contains("price"), "best-effort imports the number");
        assertTrue(res.err().contains("scale"), () -> "the guessed scale is flagged on stderr, not silent: " + res.err());
    }

    @Test
    void strictOmitsWhatTheDefaultImports(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("price.schema.json");
        Files.writeString(schema, NAME_AND_PRICE);

        CliTestSupport.Result dflt = CliTestSupport.run("model", "import-jsonschema", "--schema", schema.toString());
        assertTrue(dflt.out().contains("price"), () -> "default best-effort imports the number: " + dflt.out());

        CliTestSupport.Result strict = CliTestSupport.run(
                "model", "import-jsonschema", "--schema", schema.toString(), "--strict");
        assertEquals(0, strict.code(), strict::err);
        assertFalse(strict.out().contains("price"), () -> "--strict omits the bare number: " + strict.out());
        assertTrue(strict.out().contains("name"), "the mappable sibling still imports under --strict");
    }

    @Test
    void theDefaultRepeatCapImportsAnUnboundedArraySoNestedStructureIsReached(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("rows.schema.json");
        Files.writeString(schema, """
                { "title": "Rows", "type": "object", "properties": {
                    "rows": { "type": "array",
                              "items": { "type": "object", "properties": { "v": { "type": "string" } } } } } }
                """);   // no maxItems — best-effort caps it (default 1000) so the nested `v` field is reached
        Path model = dir.resolve("Rows.dm.json");

        CliTestSupport.Result res = CliTestSupport.run(
                "model", "import-jsonschema", "--schema", schema.toString(), "-o", model.toString());
        assertEquals(0, res.code(), res::err);
        CliTestSupport.Result describe = CliTestSupport.run("-m", model.toString(), "model", "describe");
        assertEquals(0, describe.code(), describe::err);
        assertTrue(describe.out().contains("/Root/rows/v"),
                () -> "the unbounded array imported (capped) and its nested field was reached: " + describe.out());
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
