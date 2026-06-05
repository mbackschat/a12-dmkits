package io.github.mbackschat.a12.rulekit.cli;

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

/**
 * The {@code check} verb — the authoring feedback loop the N6 baseline probe found missing: submit a
 * candidate rule (condition + error field + code) and get the real kernel's accept/reject. Thin
 * wrapper over core {@code RuleValidator.check}.
 */
class CheckVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path model(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.subscriptionJson());
        return f;
    }

    @Test
    void acceptsAValidCandidateRule(@TempDir Path dir) throws Exception {
        // --field is the error field's absolute path; --group defaults to its parent (iterate per add-on).
        var res = CliTestSupport.run("check", model(dir).toString(),
                "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", "[MonthlyFee] > 1000", "--code", "FEE_CAP");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("valid").asBoolean(), "the kernel accepts a well-formed candidate");
        assertTrue(j.get("diagnostics").isEmpty(), "with no diagnostics");
    }

    @Test
    void rejectsABadCandidateWithKernelDiagnostics(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("check", model(dir).toString(),
                "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", "@@@ not a condition @@@", "--code", "X");
        assertEquals(1, res.code(), "a rejected candidate exits non-zero");
        JsonNode j = MAPPER.readTree(res.out());
        assertFalse(j.get("valid").asBoolean(), "valid is false");
        assertTrue(j.get("diagnostics").size() > 0, () -> "with kernel diagnostics: " + res.out());
    }

    @Test
    void warnsOnInvertedRequirednessInDiagnostics(@TempDir Path dir) throws Exception {
        // The requirement written positively — FieldFilled on the error field — is kernel-valid but
        // inverted polarity (it fires when the fee IS present). check accepts it (valid stays true)
        // yet surfaces the RK_INVERTED_REQUIREDNESS lint warning in the diagnostics — the same silent
        // gap the kernel can't catch and that `add` warns on, now caught at the check stage too.
        var res = CliTestSupport.run("check", model(dir).toString(),
                "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", "FieldFilled(MonthlyFee)", "--code", "FEE_PRESENT");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("valid").asBoolean(), "the kernel accepts a well-formed (if inverted) candidate");
        assertTrue(j.get("diagnostics").toString().contains("RK_INVERTED_REQUIREDNESS"),
                () -> "the lint backstop surfaces the inverted polarity: " + res.out());
    }

    @Test
    void missingRequiredFlagFails(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("check", model(dir).toString(),
                "--field", "/Subscription/Addons/MonthlyFee", "--code", "X"); // no --condition
        assertNotEquals(0, res.code(), "a missing required flag is an error");
        assertTrue(res.err().toLowerCase().contains("condition") || res.err().toLowerCase().contains("usage"),
                () -> "names the gap: " + res.err());
    }
}
