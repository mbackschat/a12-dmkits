package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code schema} verb (CLI-SPEC §5a) — the CLI's on-demand contract surface. Two shapes: the
 * cross-cutting <b>structures</b> ({@code result}/{@code diagnostic}) as raw JSON Schema, and a verb's
 * <b>directional contract</b> ({@code schema <target> <op>}). The drift guard is the point (F11 "loud
 * detection at build"): a target-first verb's real output must conform to the universal {@code result}
 * schema, and the {@code schema add} split must hold.
 */
class SchemaVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listsTheStructureSchemasWhenBare() {
        CliTestSupport.Result res = CliTestSupport.run("schema");
        assertEquals(0, res.code(), res::err);
        for (String name : SchemaCommand.STRUCTURES) {
            assertTrue(res.out().contains(name), () -> "lists " + name + ": " + res.out());
        }
    }

    @Test
    void theApplyAndBatchFramesAreServedWithAWorkedExample() throws Exception {
        for (String frame : new String[] {"apply", "batch"}) {
            CliTestSupport.Result res = CliTestSupport.run("schema", frame);
            assertEquals(0, res.code(), res::err);
            JsonNode s = MAPPER.readTree(res.out());
            assertTrue(s.path("examples").isArray() && s.path("examples").size() > 0,
                    () -> frame + " frame carries a worked examples block: " + res.out());
        }
    }

    @Test
    void unknownSchemaFails() {
        assertEquals(2, CliTestSupport.run("schema", "nope").code());
        assertEquals(2, CliTestSupport.run("schema", "rule", "bogus").code(), "a non-existent op has no contract");
    }

    @Test
    void resultStructureSchemaIsServed() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("schema", "result");
        assertEquals(0, res.code(), res::err);
        JsonNode props = MAPPER.readTree(res.out()).path("properties");
        assertTrue(props.has("outcome") && props.has("data") && props.has("diagnostics"),
                () -> "the result structure declares the envelope keys: " + res.out());
    }

    @Test
    void addSplitsIntoRuleAddAndComputationAdd() throws Exception {
        // `schema add` is gone — it splits into the target-first contracts, each carrying the rich input.
        assertEquals(2, CliTestSupport.run("schema", "add").code(), "bare `schema add` no longer resolves");

        for (String op : new String[] {"rule add", "computation add"}) {
            CliTestSupport.Result res = CliTestSupport.run("schema", op.split(" ")[0], op.split(" ")[1]);
            assertEquals(0, res.code(), res::err);
            JsonNode c = MAPPER.readTree(res.out());
            assertEquals(op, c.path("op").asText());
            assertEquals("result", c.path("returns").asText());
            assertTrue(c.path("input").has("properties"), () -> op + " contract carries the input schema: " + res.out());
        }
    }

    @Test
    void flagOnlyOpContractHasNoInputAndReadsNoteData() throws Exception {
        // a flag-only op (group add): direction is "send nothing rich, get the envelope back"
        JsonNode add = MAPPER.readTree(CliTestSupport.run("schema", "group", "add").out());
        assertEquals("result", add.path("returns").asText());
        assertFalse(add.has("input"), "group add takes flags (in the manifest), not a rich JSON input");

        // a read op notes that its payload rides the envelope's `data`
        JsonNode read = MAPPER.readTree(CliTestSupport.run("schema", "field", "read").out());
        assertEquals("result", read.path("returns").asText());
        assertTrue(read.has("data"), "a read's contract notes the `data` direction");
    }

    @Test
    void verdictVerbsNoteTheVerdictNotData() throws Exception {
        // `model validate` / `rule check` carry their result in the ENVELOPE's valid + diagnostics, not a
        // `data` payload — the contract must say so and must NOT claim a (nonexistent) data shape. Locks the
        // VERDICT_OPS reclassification (they no longer mis-map to the retired validate.schema as a dataSchema).
        for (String verb : new String[] {"model validate", "rule check"}) {
            String[] t = verb.split(" ");
            JsonNode c = MAPPER.readTree(CliTestSupport.run("schema", t[0], t[1]).out());
            assertEquals("result", c.path("returns").asText());
            assertTrue(c.has("verdict"), () -> verb + " contract notes the verdict: " + c);
            assertFalse(c.has("data"), () -> verb + " emits no data payload, so the contract must not claim one: " + c);
            assertFalse(c.has("dataSchema"), () -> verb + " must not serve a data-shape schema: " + c);
        }
    }

    @Test
    void docInstanceVerbsSurfaceTheDocInputShape() throws Exception {
        // the gap-finder (Option C) found 3/3: `--help` promises the --instance instance shape via `schema <verb>`,
        // but the contract never served it. Now `schema model eval`/`model compute`/`rule test` carry `docInput`.
        for (String verb : new String[] {"model eval", "model compute", "rule test"}) {
            String[] t = verb.split(" ");
            JsonNode c = MAPPER.readTree(CliTestSupport.run("schema", t[0], t[1]).out());
            assertEquals("DocumentInstance", c.path("docInput").path("title").asText(),
                    () -> verb + " contract surfaces the --instance document-instance schema: " + c);
            assertTrue(c.path("docInput").path("properties").has("fields"),
                    () -> verb + " docInput describes the `fields` instance tree: " + c);
        }
    }

    @Test
    void readVerbsSurfaceTheirDataPayloadSchema() throws Exception {
        // the gap-finder found rule explain's `data` shape (operators/tree/refs/gloss) undiscoverable — now
        // every read verb with a published payload schema surfaces it as `dataSchema`.
        assertEquals("Explanation",
                MAPPER.readTree(CliTestSupport.run("schema", "rule", "explain").out()).path("dataSchema").path("title").asText());
        assertEquals("ModelCard",
                MAPPER.readTree(CliTestSupport.run("schema", "model", "describe").out()).path("dataSchema").path("title").asText());
        assertEquals("ModelUsage",
                MAPPER.readTree(CliTestSupport.run("schema", "model", "usage").out()).path("dataSchema").path("title").asText());
        assertEquals("WhereUsed",
                MAPPER.readTree(CliTestSupport.run("schema", "where-used").out()).path("dataSchema").path("title").asText());
        assertEquals("EvalDocResult",
                MAPPER.readTree(CliTestSupport.run("schema", "model", "eval").out()).path("dataSchema").path("title").asText());
        assertEquals("WorkspaceList",
                MAPPER.readTree(CliTestSupport.run("schema", "workspace", "list").out()).path("dataSchema").path("title").asText());
        assertEquals("ModelInfo",
                MAPPER.readTree(CliTestSupport.run("schema", "model", "info").out()).path("dataSchema").path("title").asText());
        assertEquals("WorkspaceGraph",
                MAPPER.readTree(CliTestSupport.run("schema", "workspace", "graph").out()).path("dataSchema").path("title").asText());
        assertEquals("WorkspaceRoles",
                MAPPER.readTree(CliTestSupport.run("schema", "workspace", "roles").out()).path("dataSchema").path("title").asText());
    }

    @Test
    void fieldAddContractCarriesTheRichInputSchema() throws Exception {
        // field add now also takes a rich <spec.json> (FEATURE-GAPS) — its contract surfaces the input
        JsonNode c = MAPPER.readTree(CliTestSupport.run("schema", "field", "add").out());
        assertEquals("result", c.path("returns").asText());
        assertTrue(c.path("input").has("properties"), () -> "field add's contract carries the input schema: " + c);
    }

    @Test
    void everyTargetFirstVerbOutputConformsToTheResultEnvelope(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        // a mutation (preview) and reads — each must emit only keys the result schema declares
        assertResultEnvelope(CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Subscription", "--name", "Discount", "--kind", "NUMBER", "--dry-run"));
        assertResultEnvelope(CliTestSupport.run("-m", model.toString(), "rule", "read", CliTestSupport.RULE_FULL_NAME));
        assertResultEnvelope(CliTestSupport.run("-m", model.toString(), "field", "read", "/Subscription/Addons/MonthlyFee"));
        assertResultEnvelope(CliTestSupport.run("-m", model.toString(), "config", "read"));
        assertResultEnvelope(CliTestSupport.run("-m", model.toString(), "model", "validate"));
    }

    /** Every top-level key the verb emits must be declared in the universal `result` schema (the F11 drift guard). */
    private void assertResultEnvelope(CliTestSupport.Result verb) throws Exception {
        assertEquals(0, verb.code(), () -> "verb failed: " + verb.err());
        JsonNode output = MAPPER.readTree(verb.out());

        JsonNode declared = MAPPER.readTree(CliTestSupport.run("schema", "result").out()).path("properties");
        assertTrue(declared.isObject() && declared.size() > 0, "result schema has properties");
        for (Iterator<String> it = output.fieldNames(); it.hasNext();) {
            String key = it.next();
            assertTrue(declared.has(key), () -> "verb emits key '" + key + "' not declared in result.schema.json");
        }
    }
}
