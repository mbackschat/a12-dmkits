package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * A waived rule reads back with its waiver on every read surface, so what a reader hands back to {@code rule add}
 * is admitted. The condition alone compares two scales, which A12 refuses without the waiver
 * ({@code MVK_INVALID_COMPARE_DEC_PLACES}, KERNEL-FINDINGS KF75); before, {@code rule read} and {@code model read}
 * returned that condition with no flag (2026-10-25 cold review, R2).
 */
class ScaleWaiverReadBackCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RULE = "/Order/ScaleCheck";

    private static Path waivedModel(Path dir) throws Exception {
        Path model = CliTestSupport.order(dir);
        Path spec = dir.resolve("waived.json");
        Files.writeString(spec, """
                { "field": "/Order/Quantity", "name": "ScaleCheck", "condition": "[Quantity] == [UnitPrice]",
                  "code": "SCALE_OK", "allowDifferingDecimals": true, "messages": [ {"locale":"en_US","text":"x"} ] }""");
        var added = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(0, added.code(), () -> added.out() + added.err());
        return model;
    }

    /** The returned condition and flag, re-added under another name: the kernel's verdict is the exit code. */
    private static CliTestSupport.Result readd(Path dir, Path model, JsonNode read) throws Exception {
        Path spec = dir.resolve("readd.json");
        String readd = MAPPER.createObjectNode().put("field", "/Order/Quantity").put("name", "ScaleAgain")
                .put("condition", read.path("condition").asText()).put("code", "SCALE_AGAIN")
                .put("allowDifferingDecimals", read.path("allowDifferingDecimals").asBoolean(false))
                .set("messages", MAPPER.readTree("[{\"locale\":\"en_US\",\"text\":\"x\"}]")).toString();
        Files.writeString(spec, readd);
        return CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString(), "--dry-run");
    }

    @Test
    void ruleReadCarriesTheWaiverAndItsTextIsAdmittedAgain(@TempDir Path dir) throws Exception {
        Path model = waivedModel(dir);
        var read = CliTestSupport.run("-m", model.toString(), "rule", "read", RULE);
        JsonNode data = MAPPER.readTree(read.out()).path("data");
        assertTrue(data.path("allowDifferingDecimals").asBoolean(), read::out);
        var again = readd(dir, model, data);
        assertEquals(0, again.code(), again::out);
    }

    @Test
    void modelReadCarriesTheWaiverToo(@TempDir Path dir) throws Exception {
        Path model = waivedModel(dir);
        var read = CliTestSupport.run("-m", model.toString(), "model", "read");
        JsonNode rule = MAPPER.readTree(read.out()).path("data").path("rules").path(RULE);
        assertTrue(rule.path("allowDifferingDecimals").asBoolean(), read::out);
        var again = readd(dir, model, rule);
        assertEquals(0, again.code(), again::out);
    }

    /** A re-expression that keeps the waiver diffs as unchanged, and both sides show it. */
    @Test
    void theModifyDiffShowsTheWaiverOnBothSides(@TempDir Path dir) throws Exception {
        Path model = waivedModel(dir);
        var modify = CliTestSupport.run("-m", model.toString(), "rule", "modify", RULE, "--condition",
                "@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) [Quantity] == [UnitPrice]", "--dry-run");
        JsonNode changed = MAPPER.readTree(modify.out()).path("changed");
        String waived = "@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) [/Order/Quantity] == [/Order/UnitPrice]";
        assertEquals(List.of(waived, waived, false), List.of(changed.path("before").asText(),
                changed.path("after").asText(), changed.path("changed").asBoolean()), modify::out);
    }

    /**
     * `rule modify` takes no spec, so the waiver is re-expressed inline. Dropping it is refused, and the corrective
     * names that inline route rather than only the spec key `rule modify` has no flag for.
     */
    @Test
    void aModifyThatDropsTheWaiverIsToldToWriteItInline(@TempDir Path dir) throws Exception {
        Path model = waivedModel(dir);
        var modify = CliTestSupport.run("-m", model.toString(), "rule", "modify", RULE, "--condition",
                "[Quantity] == [UnitPrice]", "--dry-run");
        assertTrue(modify.code() != 0 && modify.out().contains("MVK_INVALID_COMPARE_DEC_PLACES"), modify::out);
        assertTrue(modify.out().contains("at the head of `--condition`"), modify::out);
    }

    /** The control: the same text without the flag is what A12 refuses, so the flag is what the read must carry. */
    @Test
    void theConditionAloneIsRefused(@TempDir Path dir) throws Exception {
        Path model = waivedModel(dir);
        var refused = readd(dir, model, MAPPER.createObjectNode().put("condition", "[Quantity] == [UnitPrice]"));
        assertTrue(refused.code() != 0 && refused.out().contains("MVK_INVALID_COMPARE_DEC_PLACES"), refused::out);
    }
}
