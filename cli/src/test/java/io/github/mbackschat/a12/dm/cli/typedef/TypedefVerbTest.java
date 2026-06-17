package io.github.mbackschat.a12.dm.cli.typedef;

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
 * The target-first {@code typedef} group (CLI-SPEC §5) — reusable type definitions: {@code add} (--id over
 * an easy --kind), {@code read} (lists the declared ids — typedefs are a flat model-level list, not
 * path-addressed), {@code remove} (kernel-gated; a typedef still used by a field is a {@code rejected}
 * envelope, not a proactive refusal — there is no whereUsed gate for typedefs).
 */
class TypedefVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void addDeclaresATypedef(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "add", "--id", "MoneyAmount", "--kind", "NUMBER");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("MoneyAmount", j.path("changed").path("added").asText());
        assertEquals("NUMBER", j.path("changed").path("kind").asText());
        assertTrue(Files.readString(model).contains("MoneyAmount"));
    }

    @Test
    void readListsTheDeclaredIds(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "typedef", "add",
                "--id", "MoneyAmount", "--kind", "NUMBER").code());
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        JsonNode ids = j.path("data").path("typedefs");
        assertTrue(ids.isArray());
        assertTrue(M.convertValue(ids, java.util.List.class).contains("MoneyAmount"));
    }

    @Test
    void removesAnAddedTypedef(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "typedef", "add",
                "--id", "MoneyAmount", "--kind", "NUMBER").code());
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "remove", "MoneyAmount");
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertFalse(Files.readString(model).contains("MoneyAmount"));
    }
}
