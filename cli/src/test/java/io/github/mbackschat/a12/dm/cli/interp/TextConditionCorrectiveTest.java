package io.github.mbackschat.a12.dm.cli.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * G30 fallback + selection unit tests for {@link TextConditionCorrective} — the CLI text-path corrective
 * enrichment. Proves it upgrades a union fix to the operand-matched corrective when the candidate text parses
 * and classifies, and — the safety half — leaves the diagnostic UNTOUCHED (union fix verbatim) on any parse
 * failure, classification failure, or ambiguity, so it is never worse than serving the union.
 */
class TextConditionCorrectiveTest {

    private static final String UNION = "UNION FIX naming both remedies";
    private final ModelIndex index = ModelIndex.ofSelfContained(CliTestSupport.orderRuledJson());
    private final TextConditionCorrective corrective = new TextConditionCorrective();

    private static Diagnostic union(String code) {
        return new Diagnostic(Severity.ERROR, DiagnosticSource.KERNEL, Optional.of(code), "type mismatch",
                new Diagnostic.Where("/Order/R", Optional.empty()), Optional.of(UNION), Optional.empty());
    }

    private String fixFor(String code, String condition) {
        List<Diagnostic> out = corrective.enrich(List.of(union(code)),
                condition, "/Order", index);
        return out.get(0).fix().orElse("");
    }

    private static String catalogFix(String operator, String code, String... operands) {
        return OperatorCatalog.load().correctiveFix(operator, code,
                List.of(operands)).orElseThrow();
    }

    @Test
    void upgradesDateVsIsoLiteralToTheDateCorrective() {
        assertEquals(catalogFix("LessThan", "MVK_INVALID_TYPE_FOR_COMPARISON", "date", "string-literal"),
                fixFor("MVK_INVALID_TYPE_FOR_COMPARISON", "[/Order/OrderDate] < \"2024-12-31\""),
                "a classifiable date-vs-ISO-literal is upgraded off the union");
    }

    @Test
    void upgradesStringVsStringToTheStringCorrective() {
        assertEquals(catalogFix("LessThan", "MVK_INVALID_TYPE_FOR_COMPARISON", "string", "string"),
                fixFor("MVK_INVALID_TYPE_FOR_COMPARISON", "[/Order/CustomerName] < [/Order/ProductCode]"));
    }

    @Test
    void unparseableTextKeepsTheUnion() {
        // a syntactically broken candidate — the tolerant parse throws → diagnostics unchanged
        assertEquals(UNION, fixFor("MVK_INVALID_TYPE_FOR_COMPARISON", "[/Order/OrderDate] < < ((("));
    }

    @Test
    void anAbsentFieldKeepsTheUnion() {
        // the field isn't in the model → the operand doesn't classify → union kept
        assertEquals(UNION, fixFor("MVK_INVALID_TYPE_FOR_COMPARISON", "[/Order/NoSuchField] < \"2024-12-31\""));
    }

    @Test
    void ambiguousCandidateOperatorsKeepTheUnion() {
        // two candidate operators (< and >) both produce the code → no unique pin → union kept
        assertEquals(UNION, fixFor("MVK_INVALID_TYPE_FOR_COMPARISON",
                "[/Order/OrderDate] < \"a\" Or [/Order/DeliveryDate] > \"b\""));
    }


    @Test
    void upgradesPatternRightFieldToTheStringLiteralCorrective() {
        assertEquals(catalogFix("PatternMatched", "MVK_MISSING_CONST_IN_PATTERN_COMP", "string", "string"),
                fixFor("MVK_MISSING_CONST_IN_PATTERN_COMP",
                        "[/Order/CustomerName] PatternMatched [/Order/ProductCode]"));
    }

    @Test
    void upgradesPatternRightNumberToTheStringLiteralCorrective() {
        assertEquals(catalogFix("PatternMatched", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON",
                        "string", "number-literal"),
                fixFor("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON", "[/Order/CustomerName] PatternMatched 1"));
    }

    @Test
    void upgradesPatternLeftNumberToTheStringFieldCorrective() {
        assertEquals(catalogFix("PatternViolated", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON",
                        "number", "string-literal"),
                fixFor("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON", "[/Order/Quantity] PatternViolated \"A+\""));
    }

    @Test
    void aNonKernelDiagnosticIsReturnedUnchanged() {
        Diagnostic lint = new Diagnostic(Severity.WARNING, DiagnosticSource.LINT, Optional.of("RK_X"), "s",
                new Diagnostic.Where("/Order/R", Optional.empty()), Optional.of("keep"), Optional.empty());
        List<Diagnostic> out = corrective.enrich(List.of(lint), "[/Order/OrderDate] < \"2024-12-31\"",
                "/Order", index);
        assertSame(lint, out.get(0), "a non-KERNEL diagnostic is passed through untouched");
    }
}
