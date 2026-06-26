package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.RuleTestOutcome;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Scoped single-rule test (FEATURE-GAPS §3.3) — {@link KernelAdapter#testRule} reports a rule's empirical
 * polarity on a document instance, and crucially distinguishes a genuine <b>PASS</b> from a <b>SUPPRESSED</b>
 * rule: one the kernel never evaluated because a field it references is formally invalid (KERNEL-FINDINGS;
 * the formal-error-suppresses-the-rule behavior locked by {@code FormalErrorLawsTest}). A plain "did it fire"
 * verb would report both as {@code fired:false} and mislead — this is the fires / passes / suppressed law.
 */
class RuleTestLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");

    // The condition is authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to the
    // kernel — the test's own kernel-law assertion is the safety net. ABSOLUTE path; the renderer relativizes.
    private static final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return new DslRenderer().render(c);
    }

    // PriceHigh fires when UnitPrice > 5; it references /Order/UnitPrice.
    private static final String MODEL = RuntimeLaws.withRule(ORDER, "/Order", "PriceHigh", "../UnitPrice",
            "PRICE_HIGH", render(m.num("/Order/UnitPrice").gt(5)));
    private static final Locale EN = Locale.forLanguageTag("en-US");

    private static RuleTestOutcome test(String unitPrice) {
        return KernelAdapter.ofJson(MODEL)
                .testRule("/Order/PriceHigh", Map.of("Order", Map.of("UnitPrice", unitPrice)), EN);
    }

    @Test
    void firesOnAViolatingInstance() {
        RuleTestOutcome outcome = test("10.00");
        assertTrue(outcome.fired(), () -> "UnitPrice 10.00 > 5 — the rule fires: " + outcome);
        assertEquals(RuleTestOutcome.Verdict.FIRED, outcome.verdict());
    }

    @Test
    void passesOnASatisfyingInstance() {
        RuleTestOutcome outcome = test("3.00");
        assertFalse(outcome.fired(), () -> "UnitPrice 3.00 is not > 5 — the rule passes: " + outcome);
        assertEquals(RuleTestOutcome.Verdict.PASSED, outcome.verdict());
        assertTrue(outcome.suppressedBy().isEmpty(), () -> "a genuine pass has nothing suppressing it: " + outcome);
    }

    @Test
    void reportsSuppressionWhenAReferencedFieldIsFormallyInvalid() {
        // 3 decimals on a 2-decimal field → the field is formally invalid (unknown), so PriceHigh never runs.
        RuleTestOutcome outcome = test("10.999");
        assertEquals(RuleTestOutcome.Verdict.SUPPRESSED, outcome.verdict(),
                () -> "a formally-invalid referenced field suppresses the rule — NOT a pass: " + outcome);
        assertTrue(outcome.suppressedBy().stream().anyMatch(s -> s.field().endsWith("UnitPrice")),
                () -> "the suppressing field is named: " + outcome.suppressedBy());
    }
}
