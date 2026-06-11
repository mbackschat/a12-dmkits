package io.github.mbackschat.a12.dm.cli;

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
 * The {@code apply} session verb (CLI-SPEC §7) — the north-star: one read → ordered surgery on ONE
 * {@code EditSession} → one terminal kernel gate → one write, with <b>atomic rollback</b>. Distinct from
 * {@code batch} (stateless per-op dispatch): {@code apply}'s ops are op-records {@code {target,op,…args}}
 * applied to a shared model, each emitting a per-op envelope (a successful edit is {@code staged} until the
 * terminal gate commits the whole sequence). This first increment covers the structure ops (field/group).
 */
class ApplyVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void appliesAnOrderedSequenceAtomically(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                  {"target":"group","op":"add","parent":"/Order","name":"Notes"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertEquals(2, j.path("ops").asInt());
        assertTrue(j.path("written").asBoolean());
        assertEquals(2, j.path("results").size());
        assertEquals("staged", j.path("results").get(0).path("outcome").asText());
        String written = Files.readString(model);
        assertTrue(written.contains("Discount") && written.contains("Notes"));
    }

    @Test
    void rollsBackOnAFailingOp(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        // op 0 is valid; op 1 adds to a group that doesn't exist (addField fails fast) → the whole sequence rolls back
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                  {"target":"field","op":"add","group":"/Order/NoSuchGroup","name":"X","kind":"NUMBER"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(1, res.code(), () -> "a rolled-back apply exits 1: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("committed").asBoolean());
        assertEquals(1, j.path("failedAt").asInt());
        assertFalse(j.path("written").asBoolean());
        assertEquals(before, Files.readString(model), "the model is untouched after a rollback");
    }

    @Test
    void appliesStructureThenRuleInOneSession(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // the north-star: add a field, then author a rule that guards it — atomically, one write
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                  {"target":"rule","op":"add","field":"/Order/Discount","code":"DISCOUNT_NEGATIVE",
                   "condition":"FieldFilled(/Order/Discount) And [/Order/Discount] < 0",
                   "messages":[{"locale":"en_US","text":"Discount cannot be negative."}]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertTrue(j.path("written").asBoolean());
        assertEquals("staged", j.path("results").get(1).path("outcome").asText());
        assertEquals("/Order/DISCOUNT_NEGATIVE", j.path("results").get(1).path("changed").path("rule").asText());
        String written = Files.readString(model);
        assertTrue(written.contains("Discount") && written.contains("DISCOUNT_NEGATIVE"));
    }

    @Test
    void previewWritesNothing(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "--dry-run");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), "a clean preview still 'commits' the validation verdict");
        assertFalse(j.path("written").asBoolean());
        assertEquals(before, Files.readString(model), "--dry-run writes nothing");
    }
}
