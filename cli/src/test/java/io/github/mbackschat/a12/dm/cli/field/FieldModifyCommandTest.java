package io.github.mbackschat.a12.dm.cli.field;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code field modify} — re-type an existing field's config in place (FEATURE-GAPS G6). The headline is the
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
        assertFalse(hasCode(env, "RK_RETYPE_AFFECTS_READERS"),
                "a within-kind config change is not a kind change → no reader advisory");
    }

    @Test
    void warnsWhenAKindChangeAffectsReaders(@TempDir Path dir) throws Exception {
        Path m = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("retype.json");
        // CustomerName is read only by FieldFilled (kind-agnostic) + a named CustomCondition, so STRING→ENUM
        // is kernel-accepted — yet a reader exists, so the author gets a heads-up advisory to audit it.
        Files.writeString(spec, """
                {"group":"/Order","name":"CustomerName","kind":"ENUM",
                 "enum":{"values":[{"value":"RETAIL"},{"value":"WHOLESALE"}]}}""");

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertEquals(0, res.code(), res::err);
        JsonNode env = MAPPER.readTree(res.out());
        assertEquals("applied", env.path("outcome").asText());
        assertTrue(hasCode(env, "RK_RETYPE_AFFECTS_READERS"),
                () -> "a kind change with readers warns: " + res.out());
    }

    @Test
    void noAdvisoryWhenKindChangeHasNoReaders(@TempDir Path dir) throws Exception {
        Path m = freshModel(dir);                       // /Adresse/PLZ STRING, no rules
        Path spec = dir.resolve("plz-num.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"PLZ","kind":"NUMBER"}""");

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertEquals(0, res.code(), res::err);
        JsonNode env = MAPPER.readTree(res.out());
        assertEquals("applied", env.path("outcome").asText());
        assertFalse(hasCode(env, "RK_RETYPE_AFFECTS_READERS"), "no readers → no advisory");
    }

    private static boolean hasCode(JsonNode env, String code) {
        for (JsonNode d : env.path("diagnostics")) {
            if (code.equals(d.path("code").asText())) {
                return true;
            }
        }
        return false;
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
    void changesLabelInPlaceWithoutReTyping(@TempDir Path dir) throws Exception {
        Path m = freshModel(dir);                       // /Adresse/PLZ STRING
        Path spec = dir.resolve("label.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"PLZ","label":{"en_US":"Postal code"}}""");

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertEquals(0, res.code(), res::err);
        assertEquals("applied", MAPPER.readTree(res.out()).path("outcome").asText());
        assertTrue(Files.readString(m).contains("Postal code"),
                "field modify must APPLY the label, not silently drop it");
    }

    @Test
    void setsRequirednessWithoutReTyping(@TempDir Path dir) throws Exception {
        Path m = freshModel(dir);
        Path spec = dir.resolve("req.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"PLZ","required":true}""");

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertEquals(0, res.code(), res::err);
        assertTrue(Files.readString(m).contains("requirednessConfig"),
                "field modify must set requiredness with no kind given (a metadata-only edit)");
    }

    @Test
    void refusesAModifyThatChangesNothing(@TempDir Path dir) throws Exception {
        Path m = freshModel(dir);
        Path spec = dir.resolve("empty.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"PLZ"}""");

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertNotEquals(0, res.code(), "a modify with neither a re-type nor any metadata must fail, not no-op-succeed");
    }

    @Test
    void aConfigBlockWithoutAKindGetsAPreciseCorrective(@TempDir Path dir) throws Exception {
        // the probe-05 F1(b) slip: agent passes a per-kind block to amend config (here a patternMessage) but
        // forgets `kind` — without it the block can't re-type, so the edit is empty. The corrective must name
        // the kind to add (not the generic "give a kind/typedef"), and warn that a modify REPLACES the type.
        Path m = freshModel(dir);
        Path spec = dir.resolve("plz-msg.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"PLZ",
                 "string":{"patternMessages":{"en_US":"five digits"}}}""");

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertNotEquals(0, res.code(), "a lone config block (no kind) can't re-type — must fail, not no-op-succeed");
        String msg = res.out() + res.err();
        assertTrue(msg.contains("\"kind\": \"STRING\""), () -> "names the kind to add: " + msg);
        assertTrue(msg.contains("REPLACES"), () -> "warns the modify replaces the type: " + msg);
    }

    @Test
    void rejectsAnUnknownTopLevelKey(@TempDir Path dir) throws Exception {
        Path m = freshModel(dir);
        Path spec = dir.resolve("typo.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"PLZ","requried":true}""");   // typo: requried

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertNotEquals(0, res.code(), "an unknown/typo'd key must be rejected, not silently dropped");
        JsonNode env = MAPPER.readTree(res.out());
        assertEquals("rejected", env.path("outcome").asText());
        assertTrue(hasCode(env, "RK_UNKNOWN_ARG"), () -> "carries RK_UNKNOWN_ARG with a did-you-mean: " + res.out());
    }

    @Test
    void rejectsAJsonArrayAsAWrongTypeNotAnUnknownKey(@TempDir Path dir) throws Exception {
        // probe 07: a single-object spec verb handed a JSON ARRAY is an RK_INVALID_VALUE (wrong shape); the
        // envelope summary must track that — not mislabel it "unrecognized key" the way the old hardcoded summary did.
        Path m = freshModel(dir);
        Path spec = dir.resolve("arr.json");
        Files.writeString(spec, """
                [{"group":"/Adresse","name":"PLZ","kind":"STRING"}]""");
        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());
        assertNotEquals(0, res.code());
        JsonNode env = MAPPER.readTree(res.out());
        assertEquals("rejected", env.path("outcome").asText());
        assertTrue(hasCode(env, "RK_INVALID_VALUE"), () -> "an array is RK_INVALID_VALUE: " + res.out());
        String summary = env.path("summary").asText();
        assertTrue(summary.contains("invalid value") && !summary.contains("unrecognized key"),
                () -> "the summary names the wrong-type problem, not 'unrecognized key': " + summary);
    }

    @Test
    void rejectsAnUnknownNestedConfigKey(@TempDir Path dir) throws Exception {
        Path m = freshModel(dir);
        Path spec = dir.resolve("nested.json");
        Files.writeString(spec, """
                {"group":"/Adresse","name":"PLZ","kind":"STRING","string":{"maxLenght":5}}""");   // typo inside string

        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());

        assertNotEquals(0, res.code(), "a typo inside a nested config block must be rejected too");
        assertTrue(hasCode(MAPPER.readTree(res.out()), "RK_UNKNOWN_ARG"), () -> "nested unknown key flagged: " + res.out());
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
