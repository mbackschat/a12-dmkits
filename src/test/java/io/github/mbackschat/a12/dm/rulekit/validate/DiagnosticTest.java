package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.Severity;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * F4a (first increment) — the structured {@link Diagnostic}: a kernel {@code MVK_*} problem is
 * enriched by a <em>catalog-join</em> (the matching {@code invalidExample.fix} / operator
 * {@code meaning}) and the result renders as agent-parseable JSON. Lints, the gotcha/INFO channel,
 * and AST span-pointing are later increments.
 */
class DiagnosticTest {

    private final RuleValidator validator = new RuleValidator();

    /** Ordering two string fields → {@code MVK_INVALID_TYPE_FOR_COMPARISON} (a kernel error, F4). */
    private ValidationResult orderingTwoStrings() {
        return validator.check(Fixtures.orderJson(), new DraftRule(
                "/Order", "OrderingProbe", "../CustomerName",
                "[CustomerName] < [ProductCode]", "PROBE", "ERROR"));
    }

    @Test
    void kernelProblemBecomesAStructuredDiagnostic() {
        Diagnostic d = firstWithCode(orderingTwoStrings(), "MVK_INVALID_TYPE_FOR_COMPARISON");

        assertEquals(Severity.ERROR, d.severity());
        assertEquals(DiagnosticSource.KERNEL, d.source());
        assertEquals("/Order/OrderingProbe", d.where().rule());
        assertFalse(d.summary().isBlank(), "the kernel message is the WHAT");
    }

    @Test
    void catalogJoinSuppliesFixAndExplain() {
        Diagnostic d = firstWithCode(orderingTwoStrings(), "MVK_INVALID_TYPE_FOR_COMPARISON");

        assertTrue(d.fix().isPresent(), "catalog-join should reuse the operator's curated fix");
        assertTrue(d.fix().get().contains("compare strings with"),
                () -> "unexpected fix: " + d.fix().get());
        assertTrue(d.explain().isPresent(), "catalog-join should reuse the operator meaning");
    }

    @Test
    void diagnosticsSerializeToAgentParseableJson() {
        String json = orderingTwoStrings().toJson();

        assertTrue(json.contains("\"severity\""), json);
        assertTrue(json.contains("\"source\""), json);
        assertTrue(json.contains("KERNEL"), json);
        assertTrue(json.contains("\"code\""), json);
        assertTrue(json.contains("MVK_INVALID_TYPE_FOR_COMPARISON"), json);
        assertTrue(json.contains("\"fix\""), json);
        assertTrue(json.contains("\"where\""), json);
    }

    private static Diagnostic firstWithCode(ValidationResult result, String code) {
        assertFalse(result.ok(), "expected the rule to be rejected by the kernel");
        return result.diagnostics().stream()
                .filter(d -> d.code().filter(code::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected a diagnostic with code " + code + ", got: " + result.diagnostics()));
    }
}
