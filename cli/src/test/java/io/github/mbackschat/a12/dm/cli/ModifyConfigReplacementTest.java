package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code field modify} or {@code typedef modify} that carries a type replaces the element's whole per-kind
 * config, so a key the spec leaves out is removed. That replacement is the contract (the shape is the add
 * spec's, like {@code computation modify}); what these tests lock is that the removal is never silent: every
 * modify route, standalone and {@code apply}, warns with {@code RK_MODIFY_REMOVES_CONFIG} naming each key the
 * spec left out whose value is gone or reset, and stays quiet when the spec carries every key or the kind
 * changes (a re-type has its own advisory).
 * Found by the 2026-11-08 probes of tasks 02 and 10, where both agents bisected the loss by hand.
 */
class ModifyConfigReplacementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CODE = "RK_MODIFY_REMOVES_CONFIG";

    /** An e-commerce order with a constrained STRING field and a scaled NUMBER type definition. */
    private static Path model(Path dir) throws Exception {
        Path m = dir.resolve("shop.dm.json");
        CliTestSupport.run("model", "new", "--id", "shop", "--locale", "en_US", "--root", "Order", "-o", m.toString());
        run(m, dir, "field", "add", "--group", "/Order", "--name", "Code", "--kind", "STRING");
        modifyField(m, dir, """
                {"group":"/Order","name":"Code","kind":"STRING",
                 "string":{"minLength":2,"maxLength":9,"pattern":"[A-Z0-9]*","patternMessage":"letters or digits"}}""");
        run(m, dir, "typedef", "add", "--id", "Money", "--kind", "NUMBER");
        modifyTypedef(m, dir, """
                {"kind":"NUMBER","number":{"maxFractionalDigits":2,"min":"0","unit":"AMOUNT"}}""");
        return m;
    }

    @Test
    void aPartialFieldBlockWarnsNamingEachRemovedKey(@TempDir Path dir) throws Exception {
        JsonNode env = modifyField(model(dir), dir, """
                {"group":"/Order","name":"Code","kind":"STRING","string":{"minLength":3}}""");

        assertEquals("applied", env.path("outcome").asText(), env::toString);
        assertEquals(List.of("string.maxLength", "string.pattern", "string.patternMessages"), removedKeys(env));
    }

    @Test
    void aBareSameKindReTypeWarnsThatItClearsTheConfig(@TempDir Path dir) throws Exception {
        JsonNode env = modifyField(model(dir), dir, """
                {"group":"/Order","name":"Code","kind":"STRING"}""");

        assertEquals(List.of("string.maxLength", "string.minLength", "string.pattern", "string.patternMessages"),
                removedKeys(env));
    }

    @Test
    void resendingEveryKeyIsSilent(@TempDir Path dir) throws Exception {
        JsonNode env = modifyField(model(dir), dir, """
                {"group":"/Order","name":"Code","kind":"STRING",
                 "string":{"minLength":3,"maxLength":9,"pattern":"[A-Z0-9]*","patternMessage":"letters or digits"}}""");

        assertEquals("applied", env.path("outcome").asText(), env::toString);
        assertEquals(List.of(), removedKeys(env), "a changed value is an edit, not a removal");
    }

    @Test
    void aNewPatternMessageIsAnEditOfTheReadBackPatternMessages(@TempDir Path dir) throws Exception {
        JsonNode env = modifyField(model(dir), dir, """
                {"group":"/Order","name":"Code","kind":"STRING",
                 "string":{"minLength":2,"maxLength":9,"pattern":"[A-Z0-9]*","patternMessage":"use A-Z or 0-9"}}""");

        assertEquals(List.of(), removedKeys(env), "patternMessage writes what field read calls patternMessages");
    }

    @Test
    void aKindChangeIsLeftToTheReTypeAdvisory(@TempDir Path dir) throws Exception {
        JsonNode env = modifyField(model(dir), dir, """
                {"group":"/Order","name":"Code","kind":"NUMBER"}""");

        assertEquals("applied", env.path("outcome").asText(), env::toString);
        assertEquals(List.of(), removedKeys(env));
    }

    @Test
    void aPartialTypedefSpecWarnsNamingEachRemovedKey(@TempDir Path dir) throws Exception {
        JsonNode env = modifyTypedef(model(dir), dir, """
                {"kind":"NUMBER","number":{"maxFractionalDigits":3}}""");

        assertEquals("applied", env.path("outcome").asText(), env::toString);
        assertEquals(List.of("number.min", "number.unit"), removedKeys(env));
    }

    @Test
    void aBareTypedefKindWarnsThatItClearsTheConfig(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        JsonNode env = run(m, dir, "typedef", "modify", "--id", "Money", "--kind", "NUMBER");

        assertEquals(List.of("number.maxFractionalDigits", "number.min", "number.unit"), removedKeys(env));
    }

    @Test
    void applyWarnsOnTheSameOpsAsTheStandaloneVerbs(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"modify","group":"/Order","name":"Code","kind":"STRING",
                   "string":{"minLength":3}},
                  {"target":"typedef","op":"modify","id":"Money","kind":"NUMBER",
                   "number":{"maxFractionalDigits":2,"min":"0","unit":"AMOUNT"}} ]""");

        JsonNode env = run(m, dir, "apply", ops.toString(), "--dry-run");

        JsonNode results = env.path("data").path("results");
        assertEquals(List.of("string.maxLength", "string.pattern", "string.patternMessages"),
                removedKeys(results.path(0)));
        assertEquals(List.of(), removedKeys(results.path(1)), "the typedef op resent every key");
    }

    /** The removed keys the advisory names, read from its {@code where.arg}s in diagnostic order. */
    private static List<String> removedKeys(JsonNode env) {
        List<String> keys = new ArrayList<>();
        for (JsonNode d : env.path("diagnostics")) {
            if (CODE.equals(d.path("code").asText())) {
                assertEquals("WARNING", d.path("severity").asText(), d::toString);
                keys.add(d.path("where").path("arg").asText());
            }
        }
        return keys;
    }

    private static JsonNode modifyField(Path m, Path dir, String spec) throws Exception {
        return withSpec(m, dir, spec, "field", "modify");
    }

    private static JsonNode modifyTypedef(Path m, Path dir, String spec) throws Exception {
        return withSpec(m, dir, spec, "typedef", "modify", "--id", "Money");
    }

    private static JsonNode withSpec(Path m, Path dir, String spec, String... verb) throws Exception {
        Path file = Files.createTempFile(dir, "spec", ".json");
        Files.writeString(file, spec);
        String[] args = new String[verb.length + 1];
        System.arraycopy(verb, 0, args, 0, verb.length);
        args[verb.length] = file.toString();
        return run(m, dir, args);
    }

    private static JsonNode run(Path m, Path dir, String... args) throws Exception {
        String[] full = new String[args.length + 2];
        full[0] = "-m";
        full[1] = m.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        CliTestSupport.Result res = CliTestSupport.run(full);
        assertEquals(0, res.code(), res::err);
        JsonNode env = MAPPER.readTree(res.out());
        assertTrue(env.path("ok").asBoolean(), env::toString);
        return env;
    }
}
