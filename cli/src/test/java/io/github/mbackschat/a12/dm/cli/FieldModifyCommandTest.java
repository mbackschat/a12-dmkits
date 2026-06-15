package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code field modify} — re-type an existing field's config in place (FEATURE-GAPS §2 G6). The headline is the
 * agent-session case: turn a plain STRING postal-code field into a 5-digit-pattern one <em>without</em> the
 * delete-and-recreate workaround. Plus the gate test (does-fire): modifying a missing field refuses, it does
 * not silently create or stack-trace.
 */
class FieldModifyCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path freshModel(Path dir) {
        Path m = dir.resolve("adr.dm.json");
        CliTestSupport.run("model", "new", "--id", "adr", "--locale", "en_US", "--root", "Adresse", "-o", m.toString());
        CliTestSupport.run("-m", m.toString(), "field", "add", "--group", "/Adresse", "--name", "PLZ", "--kind", "STRING");
        return m;
    }

    @Test
    void reTypesAStringFieldWithAPatternInPlace(@TempDir Path dir) throws Exception {
        Path m = freshModel(dir);
        Path spec = dir.resolve("plz.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"PLZ","kind":"STRING",
                 "string":{"pattern":"[0-9]{5}","patternMessage":"exactly five digits"}}""");

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertEquals(0, res.code(), res::err);
        JsonNode env = MAPPER.readTree(res.out());
        assertEquals("applied", env.path("outcome").asText());
        assertEquals("/Adresse/PLZ", env.path("changed").path("modified").asText());
        assertTrue(Files.readString(m).contains("[0-9]{5}"),
                "the pattern is persisted into the field's type — no delete-and-recreate");
    }

    @Test
    void refusesModifyingAFieldThatDoesNotExist(@TempDir Path dir) throws Exception {
        Path m = freshModel(dir);
        Path spec = dir.resolve("ghost.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"Ghost","kind":"STRING"}""");

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertNotEquals(0, res.code(), "modifying a missing field must refuse, not succeed");
        assertTrue((res.out() + res.err()).contains("/Adresse/Ghost"),
                () -> "the refusal names the missing field: " + res.out() + res.err());
    }

    @Test
    void dryRunPreviewsAndWritesNothing(@TempDir Path dir) throws Exception {
        Path m = freshModel(dir);
        String before = Files.readString(m);
        Path spec = dir.resolve("plz.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"PLZ","kind":"STRING",
                 "string":{"pattern":"[0-9]{5}","patternMessage":"exactly five digits"}}""");

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString(), "--dry-run");

        assertEquals(0, res.code(), res::err);
        assertEquals("preview", MAPPER.readTree(res.out()).path("outcome").asText());
        assertEquals(before, Files.readString(m), "--dry-run writes nothing");
    }
}
