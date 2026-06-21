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
    void readWithAnIdReadsThatOneTypedef(@TempDir Path dir) throws Exception {
        // unified read: an id reads one, no arg lists — symmetric with the family.
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "typedef", "add",
                "--id", "MoneyAmount", "--kind", "NUMBER").code());
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "read", "MoneyAmount");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        assertEquals("MoneyAmount", j.path("data").path("typedef").asText());
        assertTrue(j.path("data").has("type"), () -> "the typedef's type is reported: " + res.out());
    }

    @Test
    void readWithAnUnknownIdFails(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "read", "Nope");
        assertFalse(res.code() == 0, "an unknown typedef id is a clean failure");
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

    // G25 — `typedef add <spec.json>` for ENUM / restricted typedefs (the SME's primary typedef use case).
    // The `--kind` flag still covers a bare easy kind; the spec reuses the field-add per-kind config.

    @Test
    void addsAnEnumTypedefFromASpec(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("tier.json");
        Files.writeString(spec, """
                { "kind": "ENUM",
                  "enum": { "values": [
                    {"value":"GOLD","labels":{"en_US":"Gold"}},
                    {"value":"SILVER","labels":{"en_US":"Silver"}} ] } }""");
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "add", "--id", "Tier", spec.toString());
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());
        // the enum typedef is real and usable — a field can be typed by it
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Order", "--name", "CustomerTier", "--typedef", "Tier").code(),
                "the enum typedef is usable as a field type");
    }

    @Test
    void addsARestrictedStringTypedefFromASpec(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("email.json");
        Files.writeString(spec, """
                { "kind": "STRING",
                  "string": { "pattern": "[^@]+@[^@]+", "patternMessage": "must be an email address" } }""");
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "add", "--id", "Email", spec.toString());
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());
        assertTrue(Files.readString(model).contains("Email"));
    }

    @Test
    void rejectsLocatorOrMetadataKeysInATypedefSpec(@TempDir Path dir) throws Exception {
        // a type def carries only a type — group/name/label don't apply (auto-rejected by the typedef-add schema)
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("bad.json");
        Files.writeString(spec, """
                { "kind": "STRING", "name": "Nope", "label": {"en_US":"x"} }""");
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "add", "--id", "X", spec.toString());
        assertEquals(1, res.code(), () -> "unknown keys rejected: " + res.out());
        assertEquals("rejected", M.readTree(res.out()).path("outcome").asText());
    }

    @Test
    void rejectsBothKindFlagAndSpec(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("s.json");
        Files.writeString(spec, "{ \"kind\": \"NUMBER\" }");
        assertFalse(CliTestSupport.run("-m", model.toString(), "typedef", "add",
                "--id", "X", "--kind", "NUMBER", spec.toString()).code() == 0,
                "passing both --kind and a spec is a usage error");
    }
}
