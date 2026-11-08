package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * {@code model describe} is an agent's first read, and before CG30 it named neither a field's requiredness
 * nor the type definition behind it, so both 2026-11-08 probes of tasks 08 and 10 fell back to one
 * {@code field read} per field. The card now carries both in {@code field read}'s spelling, each only when
 * declared, and names {@code model report} as the read that lists the rules.
 */
class ModelDescribeRequirednessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path model(Path dir) throws Exception {
        Path m = dir.resolve("shop.dm.json");
        CliTestSupport.run("model", "new", "--id", "shop", "--locale", "en_US", "--root", "Order", "-o", m.toString());
        CliTestSupport.run("-m", m.toString(), "typedef", "add", "--id", "Money", "--kind", "NUMBER");
        add(m, dir, """
                {"group":"/Order","name":"Customer","kind":"STRING","required":true}""");
        add(m, dir, """
                {"group":"/Order","name":"Total","typedef":"Money"}""");
        add(m, dir, """
                {"group":"/Order","name":"Note","kind":"STRING"}""");
        return m;
    }

    private static void add(Path m, Path dir, String spec) throws Exception {
        Path file = Files.createTempFile(dir, "field", ".json");
        Files.writeString(file, spec);
        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "add", file.toString());
        assertEquals(0, res.code(), res::out);
    }

    private static JsonNode field(JsonNode card, String path) {
        for (JsonNode f : card.path("fields")) {
            if (path.equals(f.path("path").asText())) {
                return f;
            }
        }
        throw new AssertionError("no " + path + " in " + card);
    }

    @Test
    void theCardCarriesRequirednessAndTheTypeDefinition(@TempDir Path dir) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("-m", model(dir).toString(), "model", "describe");
        assertEquals(0, res.code(), res::out);
        JsonNode card = MAPPER.readTree(res.out()).path("data");

        assertTrue(field(card, "/Order/Customer").path("required").asBoolean(), card::toString);
        assertEquals("Money", field(card, "/Order/Total").path("typedef").asText(), card::toString);
        assertEquals("NUMBER", field(card, "/Order/Total").path("kind").asText(), "the kind stays the resolved one");
        assertFalse(field(card, "/Order/Note").has("required"), "an optional field carries no flag, like scale");
        assertFalse(field(card, "/Order/Note").has("typedef"));
    }

    @Test
    void theHelpNamesTheReadThatListsRules() {
        CliTestSupport.Result res = CliTestSupport.run("model", "describe", "--help");
        assertTrue(res.out().contains("model report"), res::out);
    }
}
