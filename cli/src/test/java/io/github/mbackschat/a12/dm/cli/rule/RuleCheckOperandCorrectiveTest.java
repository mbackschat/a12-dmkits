package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * G30 — operand-aware fix selection on the {@code rule check} TEXT path. A kernel-REJECTED condition text has
 * no typed AST (the kernel parser returns null), so historically the union fix (naming both remedies) was
 * served for {@code MVK_INVALID_TYPE_FOR_COMPARISON}. The upgrade classifies the candidate's comparison
 * operands via the kernel-free {@code :interpreter} parser + {@code ModelIndex} field kinds and serves the
 * operand-matched corrective (CATALOG-SPEC §6) — the same selection the typed path already does. Falls back
 * to the union on any parse/classification failure or ambiguity (see {@code TextConditionCorrectiveTest}).
 */
class RuleCheckOperandCorrectiveTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static String fixFor(String out, String code) throws Exception {
        for (JsonNode d : M.readTree(out).get("diagnostics")) {
            if (d.path("code").asText().equals(code)) {
                return d.path("fix").asText("");
            }
        }
        return null;
    }

    @Test
    void dateVsIsoLiteralGetsTheDateCorrective(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/OrderDate", "--condition", "[/Order/OrderDate] < \"2024-12-31\"", "--code", "X");
        String fix = fixFor(res.out(), "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertTrue(fix != null && fix.startsWith("one side is a DATE"),
                () -> "date-vs-ISO-literal gets the date-literal corrective, not the union: " + res.out());
    }

    @Test
    void stringVsStringGetsTheStringCorrective(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/CustomerName",
                "--condition", "[/Order/CustomerName] < [/Order/ProductCode]", "--code", "X");
        String fix = fixFor(res.out(), "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertEquals("ordering needs numbers or dates — compare the strings with == / != instead", fix,
                () -> "string-vs-string gets the string corrective, not the union: " + res.out());
    }
}
