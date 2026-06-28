package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CI-verified schema {@code examples} (CLI-SPEC §5a, the F11 "loud detection at build" guard) — a published
 * schema's worked examples must not rot:
 * <ul>
 *   <li><b>Input</b> examples (the {@code add} rule/computation specs) are <b>executed through the tool</b> —
 *       fed to {@code rule add}/{@code computation add --dry-run} and required to be accepted. A drifted
 *       spec example (a renamed key, a now-invalid condition) fails here.</li>
 *   <li><b>Output</b> examples ({@code result}, {@code diagnostic}) are statically locked to their own schema:
 *       every key declared in {@code properties}, every {@code required} key present, every enum respected.
 *       A renamed envelope key or a dropped outcome value fails here.</li>
 * </ul>
 */
class SchemaExamplesGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void inputSpecExamplesAreAcceptedByTheTool(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());

        JsonNode examples = schema("add").path("examples");
        assertTrue(examples.isArray() && examples.size() > 0, "add.schema declares examples");
        int i = 0;
        for (JsonNode ex : examples) {
            Path spec = dir.resolve("spec-" + i++ + ".json");
            Files.writeString(spec, ex.toString());
            boolean isComputation = ex.hasNonNull("computedField");
            String target = isComputation ? "computation" : "rule";
            CliTestSupport.Result res = CliTestSupport.run(
                    "-m", model.toString(), target, "add", spec.toString(), "--dry-run");
            assertEquals(0, res.code(), () -> target + " add rejected the schema example: " + res.err() + res.out());
            assertEquals("preview", MAPPER.readTree(res.out()).path("outcome").asText(),
                    () -> "the " + target + " example previews cleanly");
        }
    }

    @Test
    void applyFrameExamplesAreAcceptedByTheTool(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());

        JsonNode examples = schema("apply").path("examples");
        assertTrue(examples.isArray() && examples.size() > 0, "apply.schema declares examples");
        int i = 0;
        for (JsonNode ex : examples) {               // each example IS an ops array
            Path ops = dir.resolve("apply-ex-" + i++ + ".json");
            Files.writeString(ops, ex.toString());
            CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "--dry-run");
            assertEquals(0, res.code(), () -> "apply frame example rejected: " + res.err() + res.out());
            assertTrue(MAPPER.readTree(res.out()).path("committed").asBoolean(),
                    () -> "the apply frame example commits cleanly: " + res.out());
        }
    }

    @Test
    void batchFrameExamplesAreAcceptedByTheTool(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());

        JsonNode examples = schema("batch").path("examples");
        assertTrue(examples.isArray() && examples.size() > 0, "batch.schema declares examples");
        int i = 0;
        for (JsonNode ex : examples) {           // each example IS a batch-ops array; the MODEL placeholder → the fixture
            String ops = ex.toString().replace("MODEL", model.toString());
            Path opsFile = dir.resolve("batch-ex-" + i++ + ".json");
            Files.writeString(opsFile, ops);
            CliTestSupport.Result res = CliTestSupport.run("batch", opsFile.toString());
            assertEquals(0, res.code(), () -> "batch frame example run failed: " + res.err());
            for (JsonNode r : MAPPER.readTree(res.out())) {
                assertEquals(0, r.path("exit").asInt(), () -> "batch frame example op exits 0: " + r);
            }
        }
    }

    @Test
    void fieldAddExamplesAreAcceptedByTheTool(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());

        JsonNode examples = schema("field-add").path("examples");
        assertTrue(examples.isArray() && examples.size() > 0, "field-add.schema declares examples");
        int i = 0;
        for (JsonNode ex : examples) {
            Path spec = dir.resolve("field-" + i++ + ".json");
            Files.writeString(spec, ex.toString());
            CliTestSupport.Result res = CliTestSupport.run(
                    "-m", model.toString(), "field", "add", spec.toString(), "--dry-run");
            assertEquals(0, res.code(), () -> "field add rejected the schema example: " + res.err() + res.out());
            assertEquals("preview", MAPPER.readTree(res.out()).path("outcome").asText(),
                    "the field-add example previews cleanly");
        }
    }

    @Test
    void typedefAddExamplesAreAcceptedByTheTool(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());

        JsonNode examples = schema("typedef-add").path("examples");
        assertTrue(examples.isArray() && examples.size() > 0, "typedef-add.schema declares examples");
        int i = 0;
        for (JsonNode ex : examples) {
            Path spec = dir.resolve("typedef-" + i + ".json");
            Files.writeString(spec, ex.toString());
            CliTestSupport.Result res = CliTestSupport.run(
                    "-m", model.toString(), "typedef", "add", "--id", "Td" + i++, spec.toString(), "--dry-run");
            assertEquals(0, res.code(), () -> "typedef add rejected the schema example: " + res.err() + res.out());
            assertEquals("preview", MAPPER.readTree(res.out()).path("outcome").asText(),
                    "the typedef-add example previews cleanly");
        }
    }

    /**
     * No-drift guard for the duplicated per-kind config: the typedef-add schema reuses field-add's per-kind
     * blocks (a type def's type is the same shape as a field's type), so every per-kind block they share must
     * be byte-identical — else a field-config change would silently skip the typedef surface.
     */
    @Test
    void typedefAddPerKindBlocksMatchFieldAdd() throws Exception {
        // the per-kind CONFIG blocks must stay byte-identical (the type shape is shared); `kind` itself is
        // excluded — its description legitimately differs (a type def cannot reference another `typedef`).
        JsonNode fieldAdd = schema("field-add").path("properties");
        JsonNode typedefAdd = schema("typedef-add").path("properties");
        for (String kindBlock : List.of("string", "number", "enum", "custom",
                "date", "time", "dateTime", "dateRange", "dateFragment")) {
            assertEquals(fieldAdd.path(kindBlock), typedefAdd.path(kindBlock),
                    () -> "typedef-add '" + kindBlock + "' must stay identical to field-add (no drift)");
        }
    }

    @Test
    void documentInstanceExampleIsAcceptedByDocEval(@TempDir Path dir) throws Exception {
        // the --instance instance schema (surfaced by `schema model eval`/`compute`/`rule eval`) must stay runnable:
        // its example, fed to `model eval`, must evaluate cleanly against a matching model.
        Path model = dir.resolve("order-ruled.dm.json");
        Files.writeString(model, CliTestSupport.orderRuledJson());
        JsonNode examples = schema("document-instance").path("examples");
        assertTrue(examples.isArray() && examples.size() > 0, "document-instance.schema declares examples");
        int i = 0;
        for (JsonNode ex : examples) {
            Path doc = dir.resolve("doc-" + i++ + ".json");
            Files.writeString(doc, ex.toString());
            CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", doc.toString());
            assertEquals(0, res.code(), () -> "model eval rejected the document-instance example: " + res.err() + res.out());
            assertEquals("read", MAPPER.readTree(res.out()).path("outcome").asText(),
                    "the document-instance example evaluates cleanly");
        }
    }

    @Test
    void resultExamplesConformToTheResultSchema() throws Exception {
        JsonNode resultSchema = schema("result");
        JsonNode diagnosticSchema = schema("diagnostic");
        JsonNode examples = resultSchema.path("examples");
        assertTrue(examples.isArray() && examples.size() > 0, "result.schema declares examples");
        for (JsonNode ex : examples) {
            check(ex, resultSchema, diagnosticSchema, "result example");
        }
    }

    @Test
    void diagnosticExamplesConformToTheDiagnosticSchema() throws Exception {
        JsonNode diagnosticSchema = schema("diagnostic");
        JsonNode examples = diagnosticSchema.path("examples");
        assertTrue(examples.isArray() && examples.size() > 0, "diagnostic.schema declares examples");
        for (JsonNode ex : examples) {
            check(ex, diagnosticSchema, diagnosticSchema, "diagnostic example");
        }
    }

    /** Validate an instance object against an object schema: keys declared, required present, enums + nested objects honored. */
    private static void check(JsonNode inst, JsonNode schema, JsonNode diagnosticSchema, String where) {
        JsonNode props = schema.path("properties");
        for (String req : strings(schema.path("required"))) {
            assertTrue(inst.has(req), () -> where + " is missing required key '" + req + "'");
        }
        for (Iterator<String> it = inst.fieldNames(); it.hasNext();) {
            String key = it.next();
            assertTrue(props.has(key), () -> where + " has key '" + key + "' not declared in the schema");
            JsonNode propSchema = props.path(key);
            JsonNode value = inst.path(key);
            if (propSchema.has("enum")) {
                assertTrue(strings(propSchema.path("enum")).contains(value.asText()),
                        () -> where + " key '" + key + "' value '" + value.asText() + "' not in the declared enum");
            }
            // the diagnostics array → each entry conforms to the diagnostic schema
            if ("diagnostics".equals(key) && value.isArray()) {
                for (JsonNode d : value) {
                    check(d, diagnosticSchema, diagnosticSchema, where + ".diagnostics[]");
                }
            }
            // a nested declared object (e.g. a diagnostic's `where`) → recurse
            if ("object".equals(propSchema.path("type").asText()) && propSchema.has("properties") && value.isObject()) {
                check(value, propSchema, diagnosticSchema, where + "." + key);
            }
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static JsonNode schema(String name) throws Exception {
        try (InputStream in = SchemaExamplesGuardTest.class.getResourceAsStream("/schema/" + name + ".schema.json")) {
            assertTrue(in != null, () -> "schema resource present: " + name);
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
