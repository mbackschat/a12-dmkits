package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target-first {@code doc} group (CLI-SPEC §5) — the first verb migrated to the new surface, proving
 * the three mechanics the whole sweep rides on: the nested command tree (root → {@code doc} → {@code
 * validate}), the {@code -m/--model} context option accepted <b>before or after</b> the verb, and the
 * uniform result envelope (§3b). Subject validity rides {@code valid}; the op executing rides {@code ok}.
 */
class DocVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void validatesAValidModelWithModelBeforeTheVerb(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "doc", "validate");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("doc", j.get("target").asText());
        assertEquals("validate", j.get("op").asText());
        assertEquals("read", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean());
        assertTrue(j.get("valid").asBoolean());
        assertTrue(j.path("diagnostics").isArray());
    }

    @Test
    void acceptsTheModelOptionAfterTheVerbToo(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("doc", "validate", "-m", model.toString());
        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).get("valid").asBoolean());
    }
}
