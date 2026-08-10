package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;

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

    private static String catalogFix(String operator, String code, String... operands) {
        return OperatorCatalog.load().correctiveFix(operator, code,
                java.util.List.of(operands)).orElseThrow();
    }

    @Test
    void dateVsIsoLiteralGetsTheDateCorrective(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/OrderDate", "--condition", "[/Order/OrderDate] < \"2024-12-31\"", "--code", "X");
        String fix = fixFor(res.out(), "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertEquals(catalogFix("LessThan", "MVK_INVALID_TYPE_FOR_COMPARISON", "date", "string-literal"), fix,
                () -> "date-vs-ISO-literal gets the date-literal corrective, not the union: " + res.out());
    }

    @Test
    void stringVsStringGetsTheStringCorrective(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/CustomerName",
                "--condition", "[/Order/CustomerName] < [/Order/ProductCode]", "--code", "X");
        String fix = fixFor(res.out(), "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertEquals(catalogFix("LessThan", "MVK_INVALID_TYPE_FOR_COMPARISON", "string", "string"), fix,
                () -> "string-vs-string gets the string corrective, not the union: " + res.out());
    }

    @Test
    void patternRightFieldGetsTheStringLiteralCorrective(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/CustomerName",
                "--condition", "[/Order/CustomerName] PatternMatched [/Order/ProductCode]", "--code", "X");
        assertEquals(catalogFix("PatternMatched", "MVK_MISSING_CONST_IN_PATTERN_COMP", "string", "string"),
                fixFor(res.out(), "MVK_MISSING_CONST_IN_PATTERN_COMP"),
                () -> "a field in the regex slot gets the quoted-regex corrective: " + res.out());
    }

    @Test
    void patternRightNumberGetsTheStringLiteralCorrective(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/CustomerName",
                "--condition", "[/Order/CustomerName] PatternMatched 1", "--code", "X");
        assertEquals(catalogFix("PatternMatched", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON",
                        "string", "number-literal"),
                fixFor(res.out(), "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON"),
                () -> "a number in the regex slot gets the quoted-regex corrective: " + res.out());
    }

    @Test
    void patternLeftNumberGetsTheStringFieldCorrective(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/Quantity",
                "--condition", "[/Order/Quantity] PatternMatched \"A+\"", "--code", "X");
        assertEquals(catalogFix("PatternMatched", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON",
                        "number", "string-literal"),
                fixFor(res.out(), "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON"),
                () -> "a number field on the left gets the String-field corrective: " + res.out());
    }

    @Test
    void patternViolatedUsesTheSameOperandAwarePath(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/CustomerName",
                "--condition", "[/Order/CustomerName] PatternViolated 1", "--code", "X");
        assertEquals(catalogFix("PatternViolated", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON",
                        "string", "number-literal"),
                fixFor(res.out(), "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON"),
                () -> "PatternViolated must use the shared slot classifier: " + res.out());
    }
}
