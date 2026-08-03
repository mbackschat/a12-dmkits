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
 *   <li><b>Input</b> examples (the target-specific rule/computation add specs) are <b>executed through the tool</b> —
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
        for (String target : List.of("rule", "computation")) {
            Path model = dir.resolve(target + "-subscription.dm.json");
            Files.writeString(model, CliTestSupport.subscriptionJson());

            JsonNode examples = schema(target + "-add").path("examples");
            assertTrue(examples.isArray() && !examples.isEmpty(), target + "-add.schema declares examples");
            int i = 0;
            for (JsonNode ex : examples) {
                Path spec = dir.resolve(target + "-spec-" + i++ + ".json");
                Files.writeString(spec, ex.toString());
                CliTestSupport.Result res = CliTestSupport.run(
                        "-m", model.toString(), target, "add", spec.toString(), "--dry-run");
                assertEquals(0, res.code(),
                        () -> target + " add rejected the schema example: " + res.err() + res.out());
                assertEquals("preview", MAPPER.readTree(res.out()).path("outcome").asText(),
                        () -> "the " + target + " example previews cleanly");
                assertPreviewStatesItsGate(target + " add", res);
            }
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
            assertTrue(MAPPER.readTree(res.out()).path("data").path("committed").asBoolean(),
                    () -> "the apply frame example commits cleanly: " + res.out());
            assertPreviewStatesItsGate("apply", res);
        }
    }

    @Test
    void batchFrameExamplesAreAcceptedByTheTool(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Path computationSpec = dir.resolve("computation-spec.json");
        Files.writeString(computationSpec,
                schema("computation-add").path("examples").get(0).toString());

        JsonNode examples = schema("batch").path("examples");
        assertTrue(examples.isArray() && examples.size() > 0, "batch.schema declares examples");
        int i = 0;
        for (JsonNode ex : examples) {           // each example IS a batch-ops array; placeholders → executable fixtures
            String ops = ex.toString()
                    .replace("MODEL", model.toString())
                    .replace("SPEC", computationSpec.toString());
            Path opsFile = dir.resolve("batch-ex-" + i++ + ".json");
            Files.writeString(opsFile, ops);
            CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "batch", opsFile.toString());
            assertEquals(0, res.code(), () -> "batch frame example run failed: " + res.err());
            JsonNode envelope = MAPPER.readTree(res.out());
            assertEquals("completed", envelope.path("outcome").asText(),
                    () -> "batch frame example returns a completed aggregate envelope: " + res.out());
            assertEquals("batch", envelope.path("target").asText(),
                    () -> "batch frame example identifies the aggregate target: " + res.out());
            assertEquals("run", envelope.path("op").asText(),
                    () -> "batch frame example identifies the aggregate operation: " + res.out());
            assertTrue(envelope.path("data").path("dispatched").asBoolean(),
                    () -> "batch reports that every child was dispatched: " + res.out());
            assertTrue(envelope.path("data").path("allSucceeded").asBoolean(),
                    () -> "the schema examples contain only successful children: " + res.out());
            JsonNode results = envelope.path("data").path("results");
            assertTrue(results.isArray(),
                    () -> "batch frame example returns child results under data.results: " + res.out());
            assertEquals(ex.size(), results.size(),
                    () -> "batch frame example returns one result per child op: " + res.out());
            for (JsonNode result : results) {
                assertTrue(result.path("exit").isInt(),
                        () -> "batch frame example child declares an integer exit: " + result);
                assertEquals(0, result.path("exit").intValue(),
                        () -> "batch frame example child exits 0: " + result);
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
            assertPreviewStatesItsGate("field add", res);
        }
    }

    @Test
    void fieldModifyExamplesAreAcceptedByTheTool(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());

        JsonNode examples = schema("field-modify").path("examples");
        assertTrue(examples.isArray() && !examples.isEmpty(), "field-modify.schema declares examples");
        int i = 0;
        for (JsonNode example : examples) {
            Path spec = dir.resolve("field-modify-" + i++ + ".json");
            Files.writeString(spec, example.toString());
            CliTestSupport.Result result = CliTestSupport.run(
                    "-m", model.toString(), "field", "modify", spec.toString(), "--dry-run");
            assertEquals(0, result.code(), () -> "field modify rejected its schema example: "
                    + result.err() + result.out());
            assertEquals("preview", MAPPER.readTree(result.out()).path("outcome").asText());
            assertPreviewStatesItsGate("field modify", result);
        }
    }

    @Test
    void fieldModifyPerKindBlocksMatchFieldAdd() throws Exception {
        JsonNode fieldAdd = schema("field-add").path("properties");
        JsonNode fieldModify = schema("field-modify").path("properties");
        for (String kindBlock : List.of("string", "number", "enum", "custom",
                "date", "time", "dateTime", "dateRange", "dateFragment")) {
            assertEquals(withoutDocumentation(fieldAdd.path(kindBlock)),
                    withoutDocumentation(fieldModify.path(kindBlock)),
                    () -> "field-modify '" + kindBlock
                            + "' must retain field-add's exact structural contract");
        }
    }

    @Test
    void metaReadExamplesUseOnlyPublishedKeys() throws Exception {
        JsonNode examples = schema("meta-read").path("examples");
        assertTrue(examples.isArray() && !examples.isEmpty(), "meta-read.schema declares examples");
        for (JsonNode example : examples) {
            assertTrue(SpecKeys.forSchema("meta-read").validate(example).isEmpty(),
                    () -> "meta-read example conforms to its schema: " + example);
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
    void modelReportTreeExamplesAndBothModesStayExecutable(@TempDir Path dir) throws Exception {
        JsonNode examples = schema("model-report").path("examples");
        assertEquals(2, examples.size(), "model-report publishes a lean and a rich tree example");
        for (JsonNode example : examples) {
            assertTrue(SpecKeys.forSchema("model-report").validate(example).isEmpty(),
                    () -> "model-report tree example uses only its published keys: " + example);
        }

        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        var leanRun = CliTestSupport.run("-m", model.toString(), "model", "report",
                "--tree", "--root", "/Subscription");
        var richRun = CliTestSupport.run("-m", model.toString(), "model", "report",
                "--tree", "--root", "/Subscription",
                "--include", "rules,computations,descriptions,annotations,enum");
        assertEquals(0, leanRun.code(), leanRun::err);
        assertEquals(0, richRun.code(), richRun::err);
        JsonNode lean = MAPPER.readTree(leanRun.out()).path("data");
        JsonNode rich = MAPPER.readTree(richRun.out()).path("data");

        assertTrue(lean.path("nodes").isArray() && rich.path("nodes").isArray(),
                "both documented tree projection modes execute");
        assertTrue(java.util.stream.StreamSupport.stream(rich.path("nodes").spliterator(), false)
                        .anyMatch(node -> node.has("rules") || node.has("computation")),
                "the rich mode actually emits its logic facets");
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

    /** Remove prose-only schema keys before comparing two contracts used in different command contexts. */
    private static JsonNode withoutDocumentation(JsonNode source) {
        JsonNode copy = source.deepCopy();
        stripDocumentation(copy);
        return copy;
    }

    private static void stripDocumentation(JsonNode node) {
        if (node.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove(List.of("description", "title"));
            node.elements().forEachRemaining(SchemaExamplesGuardTest::stripDocumentation);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(SchemaExamplesGuardTest::stripDocumentation);
        }
    }

    /**
     * The receipt column for the <b>schema-demonstrated</b> mutation verbs — {@code rule add},
     * {@code computation add}, {@code field add}, {@code field modify}, {@code apply}. Those verbs carry no
     * {@code verb-examples.json} entry (their demonstration IS the schema's input example), so
     * {@code VerbDemonstrationTest}'s preview column never reaches them; together the two make the mutation
     * surface complete rather than sampled.
     *
     * <p>What it protects: {@code ConsistencyEvidence.of(x)} compiles for any witness and yields {@code none()}
     * for one that never gated, so a mis-named witness or a dropped gate degrades to <em>silence</em>, not to a
     * failure. All five of these verbs re-bind through the real oracle before writing, so the key must be there.
     */
    private static void assertPreviewStatesItsGate(String verb, CliTestSupport.Result res) throws Exception {
        assertTrue(MAPPER.readTree(res.out()).hasNonNull("verification"),
                () -> verb + ": the example gated before it previewed, so the envelope must name the gate — an "
                        + "absent `verification` means the site's witness never ran one: " + res.out());
    }

    private static JsonNode schema(String name) throws Exception {
        try (InputStream in = SchemaExamplesGuardTest.class.getResourceAsStream("/schema/" + name + ".schema.json")) {
            assertTrue(in != null, () -> "schema resource present: " + name);
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
