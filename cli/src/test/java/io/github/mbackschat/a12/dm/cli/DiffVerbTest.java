package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * The {@code diff} verb — the F8 modify-by-re-express loop over the CLI: re-express an existing
 * rule's condition and review the before/after (with lossiness disclosed) before writing it back.
 * Built on `ModelWriter.replaceCondition` + the adapter's `diff`/`canonicalConditionText`.
 */
class DiffVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Subscription + a FeeCapRule whose condition is `[MonthlyFee] > 1000`. */
    private static Path model(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleJson());
        return f;
    }

    @Test
    void showsBeforeAndAfterForAChangedCondition(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("diff", model(dir).toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME, "--condition", "[MonthlyFee] > 2000");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("changed").asBoolean(), () -> "the condition changed: " + res.out());
        assertTrue(j.get("before").asText().contains("1000"), () -> "before keeps the old bound: " + res.out());
        assertTrue(j.get("after").asText().contains("2000"), () -> "after shows the new bound: " + res.out());
    }

    @Test
    void reportsNoChangeForAnEquivalentCondition(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("diff", model(dir).toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME, "--condition", "[MonthlyFee] > 1000");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertFalse(j.get("changed").asBoolean(), () -> "same condition → no change: " + res.out());
    }

    @Test
    void rejectsAnInvalidNewCondition(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("diff", model(dir).toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME, "--condition", "@@@ not a condition @@@");
        assertNotEquals(0, res.code(), "an invalid new condition is not diffed");
        assertTrue(res.out().isBlank(), "nothing emitted to stdout for an invalid target");
    }

    @Test
    void writesTheModifiedModelWithOutput(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("modified.dm.json");
        var res = CliTestSupport.run("diff", model(dir).toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME, "--condition", "[MonthlyFee] > 2000",
                "--output", out.toString());
        assertEquals(0, res.code(), res::err);
        String written = Files.readString(out);
        assertTrue(written.contains("2000"), "the written model carries the new condition");
        // it is a valid, reloadable model that still holds the rule
        assertTrue(BoundModel.ofJson(written).toJson().contains("FeeCapRule"), "round-trips through the kernel");
    }
}
