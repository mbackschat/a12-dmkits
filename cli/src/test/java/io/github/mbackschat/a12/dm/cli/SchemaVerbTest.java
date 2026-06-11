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
        // a flag-only op (field add): direction is "send nothing rich, get the envelope back"
        JsonNode add = MAPPER.readTree(CliTestSupport.run("schema", "field", "add").out());
        assertEquals("result", add.path("returns").asText());
        assertFalse(add.has("input"), "field add takes flags (in the manifest), not a rich JSON input");

        // a read op notes that its payload rides the envelope's `data`
        JsonNode read = MAPPER.readTree(CliTestSupport.run("schema", "field", "read").out());
        assertEquals("result", read.path("returns").asText());
        assertTrue(read.has("data"), "a read's contract notes the `data` direction");
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
        assertResultEnvelope(CliTestSupport.run("-m", model.toString(), "doc", "validate"));
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
