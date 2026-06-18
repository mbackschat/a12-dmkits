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
 * The {@code modify} verb — the WRITE end of the F8 modify-by-re-express loop: re-express a rule's
 * condition (or a computation) and write the modified model back. In place by default, {@code -o}
 * redirects, {@code --dry-run} previews only (the read-only {@code diff} behavior). An invalid
 * re-expression is rejected before any write. The only re-express verb (diff was retired; --dry-run is the read-only preview).
 */
class ModifyVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Subscription + a FeeCapRule whose condition is `[MonthlyFee] > 1000`. */
    private static Path model(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleJson());
        return f;
    }

    @Test
    void writesTheModifiedModelInPlaceByDefault(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "modify",
                CliTestSupport.RULE_FULL_NAME, "--condition", "[MonthlyFee] > 2000");
        assertEquals(0, res.code(), res::err);
        // the before/after diff rides the envelope's `changed` (its own `changed` flag marks the edit)
        assertTrue(MAPPER.readTree(res.out()).get("changed").get("changed").asBoolean(),
                () -> "shows the change: " + res.out());
        // and the model FILE now carries the new bound (written in place), still kernel-reloadable
        String written = Files.readString(m);
        assertTrue(written.contains("2000"), "the in-place model carries the new condition");
        assertFalse(written.contains("> 1000") && !written.contains("2000"), "old bound replaced");
        assertTrue(BoundModel.ofJson(written).toJson().contains("FeeCapRule"), "round-trips through the kernel");
    }

    @Test
    void dryRunPreviewsAndWritesNothing(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        String before = Files.readString(m);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "modify",
                CliTestSupport.RULE_FULL_NAME, "--condition", "[MonthlyFee] > 2000", "--dry-run");
        assertEquals(0, res.code(), res::err);
        assertTrue(MAPPER.readTree(res.out()).get("changed").get("changed").asBoolean(),
                "still shows the before/after");
        assertEquals(before, Files.readString(m), "--dry-run writes nothing: the model file is untouched");
    }

    @Test
    void outputRedirectsTheWriteAndLeavesTheSourceUntouched(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        String before = Files.readString(m);
        Path out = dir.resolve("modified.dm.json");
        var res = CliTestSupport.run("-m", m.toString(), "rule", "modify",
                CliTestSupport.RULE_FULL_NAME, "--condition", "[MonthlyFee] > 2000",
                "-o", out.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(Files.readString(out).contains("2000"), "the new file carries the change");
        assertEquals(before, Files.readString(m), "with -o the source model is left untouched");
    }

    @Test
    void commentOptionSetsALeadingCommentAndASecondModifyPreservesIt(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        // --comment sets the leading ;; comment alongside the re-expression
        var set = CliTestSupport.run("-m", m.toString(), "rule", "modify",
                CliTestSupport.RULE_FULL_NAME, "--condition", "[MonthlyFee] > 2000",
                "--comment", "raised the cap to 2000");
        assertEquals(0, set.code(), set::err);
        assertTrue(Files.readString(m).contains(";; raised the cap to 2000"), "the --comment lands as a ;; line");

        // a later modify with NO --comment carries the existing comment forward (preserve-by-default)
        var again = CliTestSupport.run("-m", m.toString(), "rule", "modify",
                CliTestSupport.RULE_FULL_NAME, "--condition", "[MonthlyFee] > 3000");
        assertEquals(0, again.code(), again::err);
        String written = Files.readString(m);
        assertTrue(written.contains(";; raised the cap to 2000"), "the comment survives a comment-less modify");
        assertTrue(written.contains("3000"), "and the newest condition is applied");
    }

    @Test
    void rejectsAnInvalidReExpressionWithoutWriting(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        String before = Files.readString(m);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "modify",
                CliTestSupport.RULE_FULL_NAME, "--condition", "@@@ not a condition @@@");
        assertNotEquals(0, res.code(), "an invalid re-expression is not applied");
        assertEquals(before, Files.readString(m), "a rejected modify writes nothing");
    }

    @Test
    void needsAtLeastOneEdit(@TempDir Path dir) throws Exception {
        var none = CliTestSupport.run("-m", model(dir).toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME);
        assertNotEquals(0, none.code(), "modify needs at least one of --condition / --severity / --message");
    }
}
