package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code schema} verb (CLI-SPEC §4) — on-demand JSON Schema of a verb's output, the CLI's
 * self-describing surface. The drift guard is the point (F11 "loud detection at build"): each schema
 * must declare every top-level key the corresponding verb actually emits, so a new output field can't
 * silently slip past the published contract.
 */
class SchemaVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listsTheAvailableSchemas() {
        CliTestSupport.Result res = CliTestSupport.run("schema");
        assertEquals(0, res.code(), res::err);
        for (String name : SchemaCommand.NAMES) {
            assertTrue(res.out().contains(name), () -> "lists " + name + ": " + res.out());
        }
    }

    @Test
    void unknownSchemaFails() {
        CliTestSupport.Result res = CliTestSupport.run("schema", "nope");
        assertEquals(2, res.code());
        assertTrue(res.err().contains("unknown schema"), res::err);
    }

    @Test
    void everyEmittedTopLevelKeyIsDeclaredInItsSchema(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);

        assertSchemaCovers("describe", CliTestSupport.run("describe", model.toString()));
        assertSchemaCovers("validate", CliTestSupport.run("validate", model.toString()));
        assertSchemaCovers("ingest",
                CliTestSupport.run("ingest", model.toString(), "--rule", CliTestSupport.RULE_FULL_NAME));
        assertSchemaCovers("explain",
                CliTestSupport.run("explain", model.toString(), "--rule", CliTestSupport.RULE_FULL_NAME));
    }

    /** Every top-level property of the verb's real output must be declared in the schema's `properties`. */
    private void assertSchemaCovers(String name, CliTestSupport.Result verb) throws Exception {
        assertEquals(0, verb.code(), () -> name + " verb failed: " + verb.err());
        JsonNode output = MAPPER.readTree(verb.out());

        CliTestSupport.Result schema = CliTestSupport.run("schema", name);
        assertEquals(0, schema.code(), schema::err);
        JsonNode declared = MAPPER.readTree(schema.out()).path("properties");
        assertTrue(declared.isObject() && declared.size() > 0, () -> name + " schema has no properties");

        for (Iterator<String> it = output.fieldNames(); it.hasNext();) {
            String key = it.next();
            assertTrue(declared.has(key),
                    () -> name + " emits key '" + key + "' not declared in " + name + ".schema.json");
        }
    }
}
